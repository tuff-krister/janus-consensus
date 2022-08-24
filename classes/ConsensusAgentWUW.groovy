//  Agent that implements a consensus protocol.
//  Copyright (C) 2022 Emil Wengle

//  This program is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.

//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.

//  You should have received a copy of the GNU General Public License
//  along with this program.  If not, see <https://www.gnu.org/licenses/>.

import org.arl.fjage.AgentID
import org.arl.fjage.AgentLocalRandom
import org.arl.fjage.FjageException
import org.arl.fjage.FSMBehavior
import org.arl.fjage.Message
import org.arl.fjage.Performative
import org.arl.fjage.WakerBehavior
import org.arl.fjage.param.Parameter
import org.arl.unet.DatagramReq
import org.arl.unet.RefuseRsp
import org.arl.unet.Services
import org.arl.unet.UnetAgent
import org.arl.unet.phy.BadFrameNtf
import org.arl.unet.phy.CollisionNtf
import org.arl.unet.phy.Physical
import org.arl.unet.phy.RxFrameNtf
import org.arl.unet.phy.RxJanusFrameNtf
import org.arl.unet.phy.TxJanusFrameReq

class ConsensusAgentWUW extends UnetAgent {

    // Constants: JANUS fields
    static final int CUID = 66
    static final int APP_TYPE_NEW_ROUND = 1 // Used to request new round
    static final int APP_TYPE_CHANNEL = 2

    // Constants: fallback reference power
    static final int FALLBACK_PLVL = 185

    // Constant: shift amount, ID
    static final int ID_LSB = 26

    // Constants: shift amounts, APP_TYPE_NEW_ROUND only
    static final int ROUNDNO_LSB = 0
    static final int FORCE_BIT = 32

    // Constants: shift amounts, APP_TYPE_CHANNEL only
    static final int PERF_LSB = 24
    static final int NOISE_LSB = 0
    static final int DOPPLER_LSB = 7
    static final int DELAY_LSB = 13
    static final int RESERVED_LSB = 20

    // Constant: confidence bits
    static final int CONF_WIDTH = 3

    // Constants: subclasses of type PROPOSAL
    static final int OPINION = 0 // Send opinion with this
    static final int CONVERGED = 1 // Send notification of convergence
    static final int START_OVER = 2 // Start over regardless of state
    static final int NEXT_ROUND = 3 // Start over only in ``done'' state

    // Constant: USER channel
    static final int USER = 4

    // Constants: shift amounts for disagreements in OFDM
    static final int NOISE = 0
    static final int DOPPLER = 1
    static final int DELAY = 2

    // Constants: time to wait for a packet reception in milliseconds
    static final int TIMEOUT = 5000
    static final int ACK_DELAY = 1000

    static final int MAX_SEND_TRIES = 8

    static final List<String> KEY_IDX = new ArrayList<String>()
    static final Map<String, Integer> PARAM_LSB = new TreeMap<String, Integer>()
    static final Map<String, Integer> WIDTH_LSB = new TreeMap<String, Integer>()
    static final List<Integer> lsbs = [NOISE_LSB, DOPPLER_LSB, DELAY_LSB, RESERVED_LSB]

    static {
        // Set keys; useful when constructing opinion blocks
        KEY_IDX[NOISE] = "noise"
        KEY_IDX[DOPPLER] = "doppler"
        KEY_IDX[DELAY] = "delay"
        KEY_IDX.eachWithIndex { it, idx ->
            WIDTH_LSB[it] = lsbs[idx+1] - lsbs[idx]
            PARAM_LSB[it] = lsbs[idx]
        }
    }

    // Constants: state names
    static final String S_MESSAGE = "message"
    static final String S_DONELISTEN = "listen"
    static final String S_ADAPT = "adapt"
    static final String S_DONE = "done"
    static final String S_GO = "go"
    static final String S_RESTART = "report"

    static final String title = "Consensus OFDM agent, WUWNet edition"
    static final String description = ("Agent that estimates channel"
    //    + " from an optimisation problem with constraints on delay and "
    //    + "Doppler spreads.")
        + "properties and comes to a consensus with other agents in the "
        + "network, based on some constraints.")
    
    // Memory
    int timeMultiplier = 1
    int badOFDMs
    int timesTXd
    int reason
    boolean opinionLocked // Ignore new opinions in done state after half time
    volatile int sessionID
    boolean hasReported

    // Design parameters
    int ofdmTime = 60000 // Millis
    int minimumListenTime = TIMEOUT // Millis
    int meanExp = ACK_DELAY // Millis, random addition with Exp distro
    float averagingStep = 1.0 // 0.5
    boolean jitter = true

    // Statistics
    int timesAdapted
    long startTime
    long stopTime
    int txesThisRound
    int collisionAll // All-time
    int collision // This round
    int badJanusAll // All-time
    int badJanus // This round
    int rxAll // All-time
    int rx // This round
    // Below variable can be used to enable "finishing touches" mode
    volatile int timesGoneBack // Times heard different opinion after consensus

    enum DesignParams implements Parameter {
        averagingStep,
        jitter,
        ofdmTime,
        minimumListenTime,
        meanExp,
    }

    // Declare parameterless constructor (it won't exist implicitly if any
    // constructors with parameters are declared)
    ConsensusAgentWUW() {
    }

    // For compatibility with startup script.
    ConsensusAgentWUW(int ofdmTime) {
        setOfdmTime(ofdmTime)
    }
    
    ConsensusAgentWUW(int ofdmTime, int minimumListenTime, int meanExp) {
        setOfdmTime(ofdmTime)
        setMinimumListenTime(minimumListenTime)
        setMeanExp(meanExp)
    }

    void setOfdmTime(int ofdmTime) {
        if (ofdmTime < 0) {
            log.warning "Set ofdmTime to $ofdmTime failed; can't be negative"
        } else {
            this.ofdmTime = ofdmTime
        }
    }

    void setMinimumListenTime(int minimumListenTime) {
        if (minimumListenTime < 0) {
            log.warning "Tried to set minimumListenTime to illegal value $minimumListenTime"
        } else {
            this.minimumListenTime = minimumListenTime
        }
    }

    void setMeanExp(int meanExp) {
        if (meanExp <= 0) {
            log.warning "Tried to set meanExp to illegal value $meanExp"
        } else {
            this.meanExp = meanExp
        }
    }

    long getTime() {
        phy.time
    }

    // Get max missed packets
    int getMaxRetries() {
        def uwlink = agentForService(Services.LINK)
        uwlink.maxRetries ?: 2
    }

    // Quick way of getting node address
    int getAddress() {
        def node = agentForService(Services.NODE_INFO)
        while (node.address == null) {} // Stall until the node has an address
        node.address
    }

    /** 
     * Maps node IDs to opinions and confidence.
     */
    Map<Integer, Map> otherNodes = new TreeMap<Integer, Map>()
    /**
     * Holds this node's opinion on parameters.
     */
    Map<String, Number> channelOpinion = new TreeMap<String, Number>()
    /**
     * Holds this node's confidence in its opinion on parameters.
     */
    Map<String, Integer> channelConfidence = new TreeMap<String, Integer>()
    /**
     * The node's state machine behaviour.
     */
    FSMBehavior behaviour

    AgentID phy
    
    @Override
    void startup() {
        // Lets us receive notifications about RX'd frames
        subscribeForService(Services.PHYSICAL)
        // Make sure we get the cargo right
        makeUpOpinion()
        phy = agentForService(Services.PHYSICAL)
        phy[Physical.JANUS].powerLevel = -20 // Just for testing
        // Insert a finite state machine behaviour here
        behaviour = add new FSMBehavior()
        behaviour.add new FSMBehavior.State(S_GO) {
            @Override
            void onEnter() {
                log.fine "_STATE_ Entering $S_GO"
                setListenTimer((minimumListenTime + (int) AgentLocalRandom.current()
                .nextExp(1/meanExp))*timeMultiplier)
                timeMultiplier = 1
            }
        }
        behaviour.add new FSMBehavior.State(S_DONELISTEN, {
            // Here we can forget that we reported anything
            log.fine "_STATE_ Entering $S_DONELISTEN"
            // Wait for at least TIMEOUT milliseconds
            if (!timesTXd || timesTXd > 0 && timesTXd <= getMaxRetries()
                    && otherNodes.isEmpty()) {
                // If we have not TX'd, message is next
                log.fine "Listen time is up. Has not TX'd, so going there"
                behaviour.nextState = S_MESSAGE
            } else if (otherNodes.isEmpty() && !timesAdapted) {
                // If nothing is heard and we have TX'd, done is next
                // Does not happen if we heard anything anytime this round
                log.info "_END_ Heard nothing, and we have TX'd 3 times. We gave up."
                behaviour.nextState = S_DONE
            } else {
                // If something is heard and we have TX'd, adapt is next
                log.fine("We have TX'd, and we have heard something at "
                    + "one point. Adapting")
                behaviour.nextState = S_ADAPT
            }
        })
        behaviour.add new FSMBehavior.State(S_MESSAGE, {
            log.fine "_STATE_ Entering $S_MESSAGE"
            // Send our opinion, then proceed to listen
            sendOpinion()
            log.fine "Opinion sent. Going back to listening mode"
            behaviour.nextState = S_GO
            // Extra time to account for resetting the timer
            timeMultiplier = 3
        })
        behaviour.add new FSMBehavior.State(S_ADAPT, {
            log.fine "_STATE_ Entering $S_ADAPT"
            timesAdapted++
            // Adapt our opinion towards a ``centre of mass''
            if (makeCompromise()) {
                // If we land on CoM, proceed to done
                // Send our CoM one last time, then we are done
                // sendFinalOpinion()
                log.fine("No change from moving to ``centre of mass''. "
                + "Broadcasting one last time")
                setOFDMParams()
                behaviour.nextState = S_DONE
            } else {
                // Otherwise, proceed to message
                timesTXd = 0
                log.fine("Opinion changed by moving to ``centre of mass''. "
                + "Broadcasting new opinion")
                behaviour.nextState = S_MESSAGE
            }
        })
        // We are done here and should be speaking OFDM
        // However, hearing an opinion that we did not agree on can pull
        // the agent into the consensus loop again
        behaviour.add new FSMBehavior.State(S_DONE) {
            @Override
            void onEnter() {
                log.fine "_STATE_ Entering $S_DONE"
                if (reason != START_OVER) {
                    sendFinalOpinion()
                }
                stopTime = getTime()
                hasReported = false
            }
        }
        behaviour.add new FSMBehavior.State(S_RESTART, {
            // Reset everything
            // Reset acknowledgement and lost OFDM packets
            log.fine "_STATE_ Entering $S_RESTART"
            if (reason == NEXT_ROUND) {
            //  def tdelta = stopTime - startTime
            //  send trace(null, new EndOfConsensusNtf(
            //      stepsTaken: timesAdapted, roundNumber: sessionID,
            //      channelProps: channelOpinion, timeMillis: tdelta/1000)) 
                ensureReport()
            }
            sessionID++
            opinionLocked = false
            sendNewRound()
            badOFDMs = 0
            // FOCUS on the consensus stage
            timesTXd = 0
            resetStats()
            timesGoneBack = 0
            otherNodes.clear()
            makeUpOpinion()
         // send new StartConsensusReq(recipient: getAgentID())
            behaviour.nextState = S_GO
            startTime = getTime()
            // TODO add check of overheard opinion
        })
        behaviour.setInitialState S_GO
    }

    void ensureReport() {
        if (hasReported) return // We just need to make sure we reported
        def tdelta = stopTime - startTime
        send trace(null, new EndOfConsensusNtf(
            stepsTaken: timesAdapted, roundNumber: sessionID,
            channelProps: channelOpinion, timeMillis: tdelta/1000)) 
        log.info """_RES_ End of consensus round $sessionID
Reached consensus in $timesAdapted round(s)
Landed on $channelOpinion
Attempt took ${String.format("%d", (int)(tdelta/1000))} ms
Transmitted $txesThisRound times
Went back $timesGoneBack times
Experienced $collision collisions
Missed $badJanus of ${badJanus + rx} packets;"""
        hasReported = true
    }

    def setListenTimer(int millis) {
        final def currentPacks = otherNodes.size()
        add new WakerBehavior(millis, {
            if (otherNodes.size() == currentPacks
                && behaviour.getCurrentState() == S_GO) {
                behaviour.nextState = S_DONELISTEN
            }
        })
    }

    // Called when we have reached the ``DONE'' state.
    def setOFDMParams() {
        log.fine "_TRACE_ Setting OFDM"
        try {
            phy[USER].modulation = "ofdm"
        } catch (FjageException e) {
            log.warning("Failed to set modulation to OFDM on "
                + "the data channel. Modem is most likely simulated")
        }
        // End OFDM stage
        final def session = sessionID
        final def goBack = timesGoneBack
        add new WakerBehavior((int)(ofdmTime/2), { 
            if (session == sessionID
                && goBack == timesGoneBack) {
                // Half time in OFDM mode reached. Opinion is now locked
                opinionLocked = true
                // Report in
                ensureReport()
                resetStats()
            }
        })
        add new WakerBehavior(ofdmTime, { 
            if (behaviour.getCurrentState() == S_DONE
                    && session == sessionID
                    && goBack == timesGoneBack) {
                reason = NEXT_ROUND
                send new StartConsensusReq(recipient: getAgentID())
                log.info "Time for OFDM is up. Starting new consensus phase"
            }
        })
    }

    @Override
    Message processRequest(Message req) {
        log.fine "_TRACE_ Got request: type ${req.getClass().getSimpleName()}"
        if (req instanceof StartConsensusReq) {
            if (behaviour.getCurrentState() != S_DONE) {
                // Cannot start
                return new RefuseRsp(req,
                    "Agent is already in a consensus process")
            }
            try {
                // If the darecipient: getAgentID()ta channel does not support OFDM, this will fail
                phy[USER].modulation = "ofdm"
            } catch (FjageException e) {
                // log.warning("Modem does not support OFDM. "
                //     + "The consensus process will be simulated")
            }
            log.info("Starting consensus process")
            behaviour.nextState = S_RESTART
            return new Message(recipient: req.sender, inReplyTo: req,
                performative: Performative.AGREE)
        } else if (req instanceof CancelConsensusReq) {
            if (behaviour.getCurrentState() != S_DONE) {
                behaviour.nextState = S_DONE
                reason = START_OVER
                log.info "Consensus process aborted"
                return new Message(inReplyTo: req, recipient: req.sender,
                    performative: Performative.AGREE)
            }
            return new RefuseRsp(req, "No consensus process is running")
        }
        null
    }

    // Fabricates an opinion with confidence numbers.
    def makeUpOpinion() {
        log.fine "_TRACE_ Generating starting opinion"
        def rng = AgentLocalRandom.current()

        channelOpinion["doppler"] = (float)rng.nextInt(64)/2.0F
        channelOpinion["delay"] = rng.nextInt(128)
        channelOpinion["noise"] = 32 + rng.nextInt(128)
        channelConfidence["doppler"] = 1
        channelConfidence["delay"] = 1
        channelConfidence["noise"] = 1

        // log.info "Fabricated a channel estimate"
        log.fine "Our fabricated channel estimate is " + channelOpinion
        // log.fine "Our confidence in our opinion is " + channelConfidence
    }
    @Override
    void processMessage(Message msg) {
        if (msg instanceof RxJanusFrameNtf
            && msg.classUserID == CUID) {
            // Handle the consensus packet
            switch (msg.appType) {
                case APP_TYPE_CHANNEL:
                    // Should still respond to accepts or acknowledgements
                    // FOCUS on this, because this is what we want to do!
                    // We got another packet, so we got a response
                    rx++
                    processOpinionNtf(msg)
                break
                case APP_TYPE_NEW_ROUND:
                    rx++
                    processNewRoundNtf(msg)
                break
                default:
                    log.warning(String.format("Got unexpected appType %d",
                                           msg.appType))
                break
            }
        } else if (msg instanceof RxFrameNtf) {
            // Only matters if we are in OFDM state
            if (behaviour.getCurrentState() == S_DONE
                && msg.type == USER) {
                badOFDMs = Math.max(badOFDMs - 1, 0)
            }
        } else if (msg instanceof BadFrameNtf) {
            if (msg.type == Physical.JANUS) {
                log.fine "_LOSS_ JANUS packet lost"
                badJanus++
            } else if (behaviour.getCurrentState() == S_DONE
                && msg.type == USER) {
                // Only matters if we are in OFDM state
                badOFDMs++
                if (badOFDMs > getMaxRetries()) {
                    // Restart
                    // log.info "Lost too many packets. Initiating consensus"
                    send new StartConsensusReq(recipient: getAgentID())
                }
            }
        } else if (msg instanceof CollisionNtf) {
            if (msg.type == Physical.JANUS) {
                log.fine "_LOSS_ JANUS packet collision"
                collision++
            }
        }
    }

    void processNewRoundNtf(Message msg) {
        def roundNumber = (int) subint(msg.appData, ROUNDNO_LSB, 32)
        def forceBit = subint(msg.appData, FORCE_BIT, 1)
        // log.info "_CHECK_ Got round number $roundNumber vs sID $sessionID"
        if (forceBit) {
            send new CancelConsensusReq()
            // Give the agent a chance to abort first
            add new WakerBehavior(1, {
                send new StartConsensusReq(recipient: getAgentID())
            })
        } else {
            if (behaviour.getCurrentState() == S_DONE
                && (roundNumber > sessionID // 
                    || roundNumber < 0 && sessionID > 0)) {
                sessionID = roundNumber - 1 // Cancel out increment in FSM
                reason = NEXT_ROUND
                send new StartConsensusReq(recipient: getAgentID())
                log.fine "New round starting as requested"
            }
        }
    }

    void sendOpinion() {
        sendOpinion(OPINION)
    }

    void sendFinalOpinion() {
        sendOpinion(CONVERGED)
    }

    void sendStartOver() {
        sendSessionStart(true)
    }

    void sendNewRound() {
        sendSessionStart(false)
    }

    void sendSessionStart(boolean force) {
        log.fine "_TRACE_ Sending new round, ${force ? "" : "not "}forced"
        def appData = ((force ? 1L : 0L) << FORCE_BIT
            | sessionID & subint(-1L, ROUNDNO_LSB, 32))
        def req = new TxJanusFrameReq(classUserID: CUID,
            appType: APP_TYPE_NEW_ROUND, appData: appData)
        sendWhenNotBusy(req, -1)
    }

    void sendWhenNotBusy(Message req, int attempt) {
        // TODO make fully JANUS compliant
        def mac = agentForService(Services.MAC)
        if (attempt < MAX_SEND_TRIES) {
            add new WakerBehavior(50*(int)Math.pow(2, attempt), {
                if (mac.channelBusy) {
                    sendWhenNotBusy(req, ++attempt)
                } else {
                    phy << req
                }
            })
        } else {
            log.warning "Gave up on request ${req}."
        }
    }

    void sendOpinion(int perf) {
        log.fine "_TRACE_ Sending opinion of type $perf"
        // I stopped hard-coding numbers
        def appData = buildADB(perf)
        def req = new TxJanusFrameReq(classUserID: CUID,
            appType: APP_TYPE_CHANNEL, appData: appData) 
        sendWhenNotBusy(req, -1)
        timesTXd++
        txesThisRound++
    }

    // Construct the ADB.
    def buildADB(int perf) {
        log.fine "_TRACE_ Building ADB"
        def appData = (long)getAddress() << ID_LSB | perf << PERF_LSB
        KEY_IDX.each {
            appData |= (long)transform(it) << PARAM_LSB[it]
        }
        appData
    }

    // Deconstructs the ADB.
    def unbuildADB(long appData) {
        log.fine "_TRACE_ Unbuilding ADB ${Long.toHexString(appData)}"
        def rxOpinion = new TreeMap<String, Number>()
        def rxConfidence = new TreeMap<String, Integer>()
        def from = (int)subint(appData, ID_LSB, 8) // should not be hard-coded!
        KEY_IDX.each {
            rxOpinion[it] = antiTransform((int)subint(appData, PARAM_LSB[it],
                WIDTH_LSB[it]), it)
            rxConfidence[it] = 1+subint(appData, RESERVED_LSB, CONF_WIDTH)
        }
        [from, rxOpinion, rxConfidence]
    }

    // Extracts a subset of a long integer.
    def subint(long num, int lsb, int width) {
        def mask = (long) Math.pow(2, width)-1 << lsb
        (num & mask) >>> lsb
    }

    // Transform variables to a format supported by our protocol.
    def transform(String key) {
        def quantity = channelOpinion[key]
        def result = 0
        switch (key) {
            case "noise":
                result = -32
                if (quantity < 0) {
                    quantity += phy.refPowerLevel
                }
            case "delay":
                result += quantity
                result = (int)Math.min(subint(-1L, 0, WIDTH_LSB["delay"]),
                    Math.max(0, result))
            break
            case "doppler":
                result = (int)Math.min(subint(-1L, 0, WIDTH_LSB["doppler"]),
                    Math.max(0, 2*quantity))
            break
            default:
                throw new FjageException("Unknown key `$key'")
        }
        result
    }

    // Inverse transform of variables.
    def antiTransform(int mapped, String key) {
        def result = 0
        switch (key) {
            case "noise":
                result = 32
            case "delay":
                result += mapped
            break
            case "doppler":
                result = (float)mapped / 2.0F
            break
            default:
                throw new FjageException("Unknown key `$key'")
        }
        result
    }

    // FOCUS on this part
    void processOpinionNtf(Message msg) {
        log.fine "_TRACE_ Processing opinion message $msg"
        // First find type
        def appData = msg.appData
        def performative = (appData & 3 << PERF_LSB) >> PERF_LSB
        def from = (appData & 255 << ID_LSB) >> ID_LSB
        switch (performative) {
            case OPINION:
            case CONVERGED:
                // Assess the proposal
                // TODO let the node go back to adaptation state
                if (!opinionLocked) {
                    log.fine "Received opinion from $from. Registering it"
                    registerOpinion(appData)
                }
                // The opinion from our peers may be stale next round
                break
            // Future behaviour should use a different app type
            default:
                log.warning "Unknown performative $performative"
        }
    }

    def registerOpinion(long appData) {
        log.fine ("_TRACE_ Registering opinion from ADB "
            + "${Long.toHexString(appData)}")
        def unbuiltADB = unbuildADB(appData)
        def peerID = unbuiltADB[0]
        def peerOpinion = unbuiltADB[1..<unbuiltADB.size()]
        registerOpinion peerID, peerOpinion
        log.fine("Node $peerID proposes ${peerOpinion[0]} with "
            + "confidence ${peerOpinion[1]}")
        if (behaviour.getCurrentState() == S_DONE
                && peerOpinion[0] != channelOpinion) {
            log.info("Heard a different opinion than consensus. Enabling "
                    + "fine-tuning")
            timesGoneBack++
            log.fine("Peer opinion ${peerOpinion[0]} had changed away from"
                + " consensus ${channelOpinion}")
            behaviour.nextState = S_GO
        } else if (behaviour.getCurrentState() == S_GO) {
            behaviour.reenterState()
        }
    }

    def registerOpinion(int from, List peerOpinion) {
        otherNodes[from] = [opinion: peerOpinion[0],
            confidence: peerOpinion[1]]
    }

    def makeCompromise() {
        log.fine "_TRACE_ Finding compromise and updating to it"
        def knownNodes = otherNodes.keySet()
        def compromiseOpinion = new TreeMap<String, Number>()
        def compromiseConfidence = new TreeMap<String, Integer>()
        def allConsensus = true
        // Register all opinions
        KEY_IDX.each { param ->
            def allVotes = new TreeMap<Integer, List>()
            knownNodes.each { nodeID ->
                allVotes[nodeID] = [otherNodes[nodeID]["opinion"][param],
                    otherNodes[nodeID]["confidence"][param]]
            }
            // We need our own vote, too!
            allVotes[getAddress()] = [channelOpinion[param],
                channelConfidence[param]]
            // Check all opinions to see if they are equal
            allConsensus = allConsensus && areAllOpinionsEqual(allVotes)
            // Handle each parameter
            def voteResult = makeCompromise(param, allVotes)
            compromiseOpinion[param] = voteResult["opinion"]
            compromiseConfidence[param] = voteResult["confidence"]
        }
        def pastOpinion = channelOpinion.clone()
        channelOpinion = moveTowards(compromiseOpinion)
        // TODO how to compound confidence? Later work, this ``stone soup''
        // Trying more robust approach: are all opinions equal?
        // Experimenting with forgetting all opinions between runs
        // How will this affect consensus? Much in our favour!
        otherNodes.clear()
        allConsensus
    }

    def perturb(String param, Number source) {
        // log.fine "_TRACE_ Perturbing $param"
        def halfWidth = 0.5
        // Different state variables have different step size
        switch (KEY_IDX.indexOf(param)) {
            case DELAY:
            case NOISE:
                break
            case DOPPLER:
                halfWidth = 0.25
                break
            default:
                log.warning "Unknown key `$param'"
                return 0
        }
        def rng = AgentLocalRandom.current()
        source + rng.nextDouble(-halfWidth, halfWidth)
    }

    def moveTowards(Map compromise) {
        def average = new TreeMap<String, Number>()
        compromise.keySet().each {
            def com = (averagingStep*compromise[it]
                + (1-averagingStep)*channelOpinion[it])
            switch (it) {
                case "delay":
                case "noise":
                    com = (int)roundTowards(com, compromise[it], false)
                break
                case "doppler":
                    // Because we are working in half-hertz
                    com = roundTowards(2*com, 2*compromise[it], false)/2.0F
                break
                default: 
                    throw new FjageException("Unsupported parameter $it")
            }
            average[it] = com
        }
        // log.fine "Our new opinion is $average"
        average
    }

    /**
     * Test equality of all known opinions on one state variable.
     */
    def areAllOpinionsEqual(Map opinions) {
        log.fine "_TRACE_ Testing opinions"
        def opinionValues = opinions.collect { it.value[0] }
        opinionValues.every { it == opinions[getAddress()][0] }
    }

    /**
     * Find the average of all registered opinions on a state variable. 
     */
    def makeCompromise(String key, Map allVotes) {
        // log.fine "_TRACE_ Compromising on $key"
        def tally = new TreeMap<Number, Integer>()
        // Tally up all votes: list is [value, confidence]
        allVotes.each { dontCare, it ->
            try {
                // log.fine "Registering ${it[0]}: ${it[1]}"
                tally[it[0]] += it[1]
            } catch (NullPointerException e) {
                tally[it[0]] = it[1]
            }
        }
        def choice = 0
        def strongestVote = 0
        def totalVote = 0
        def jitter = timesGoneBack ? false : this.jitter
        def floorInstead = timesGoneBack
        // Our compromise strategy
        switch (key) {
            case "foo":
                // weighted majority vote
                tally.each { val, vote ->
                    if (vote > strongestVote) {
                        choice = val
                        strongestVote = vote
                    }
                }
            break
            case "bar":
            case "noise":
            case "delay":
            case "doppler":
                // weighted average
                tally.each { val, maybeVote ->
                    def vote = maybeVote ?: 1 // Null check
                    choice += val*vote
                    totalVote += vote
                    if (vote > strongestVote) {
                        strongestVote = vote
                    }
                }
                choice /= totalVote
                def perturbedChoice = (jitter ? perturb(key, choice)
                        : choice)
                if (KEY_IDX.indexOf(key) == DOPPLER) {
                    // Allow half-steps in Doppler -- didn't see this before
                    // If we have gone back from OFDM mode, take the floor
                    choice = (floorInstead ? Math.floor(2*perturbedChoice)
                        : Math.round(2*perturbedChoice))/2
                } else {
                    choice = (int) (floorInstead ? Math.floor(perturbedChoice)
                        : Math.round(perturbedChoice))
                }
            break
            default:
                throw new FjageException("Unknown key $key")
        }
        strongestVote = Math.min(strongestVote, 16) // prevent overflow
        [opinion: choice, confidence: strongestVote]
    }

    void resetStats() {
        txesThisRound = 0
        startTime = 0L
        stopTime = 0L
        timesAdapted = 0
        rxAll += rx
        collisionAll += collision
        badJanusAll += badJanus
        rx = 0
        badJanus = 0
        collision = 0
        // Cannot reset timesGoneBack because we rely on it
    }

    def roundTowards(Number num, Number to, boolean pow2) {
        if (pow2) {
            num < to ? nextPow2(num) : prevPow2(num)
        } else {
            num < to ? Math.ceil(num) : Math.floor(num)
        }
    }

    def prevPow2(Number x) {
        Math.pow(2, Math.floor(Math.log(x)/Math.log(2)))
    }

    def nextPow2(Number x) {
        Math.pow(2, Math.ceil(Math.log(x)/Math.log(2)))
    }

    void shutdown() {
        super.shutdown()
        log.info("_END_ JANUS packets lost: $badJanusAll/${rxAll+badJanusAll} "
            + "(${badJanusAll/(rxAll + badJanusAll) * 100}%)")
    }

    List<Parameter> getParameterList() {
        allOf DesignParams
    }
}
