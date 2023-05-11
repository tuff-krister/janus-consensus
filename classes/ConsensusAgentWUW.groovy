//  Agent that implements our proposed consensus protocol.
//  Copyright (C) 2023 Emil Wengle

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
    /** Class user ID of the protocol. */
    static final int CUID = 66
    /** Use when a node cannot maintain the consensus process, _e.g._ due to
     *  low energy.
     */
    static final int APP_TYPE_PANIC = 0
    /** Use when a node requests a new round of consensus. */
    static final int APP_TYPE_NEW_ROUND = 1 // Used to request new round
    /** Use to indicate that the packet contains channel state variables. */
    static final int APP_TYPE_CHANNEL = 2

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

    // Constant: confidence bits (not actively used)
    static final int CONF_WIDTH = 3

    // Constants: subclasses of type PROPOSAL
    static final int OPINION = 0 // Send opinion with this
    static final int CONVERGED = 1 // Send notification of convergence

    // Constants: used to determine whether we should send the final opinion
    // when we enter the done state, and whether we should log the final
    // opinion
    static final int START_OVER = 2 // Start over regardless of state
    static final int NEXT_ROUND = 3 // Start over only in ``done'' state

    // Constant: USER channel
    static final int USER = 4

    // Constants: indexes for a helper variable
    static final int NOISE = 0
    static final int DOPPLER = 1
    static final int DELAY = 2

    // Constants: default time to wait for a packet reception in milliseconds
    static final int OFDM_TIME = 120000
    static final int TIMEOUT = 3000
    static final int EXP_DELAY = 3000

    // Constant: how many times we can back off before giving up on sending
    // a packet
    static final int MAX_SEND_TRIES = 8

    // Mappings and lists for least significant bits, used to assemble and
    // parse ADBs
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
    static final String description = ("Agent that estimates channel "
        + "properties and comes to a consensus with other agents in the "
        + "network, based on some constraints.")
    
    // Memory variables
    int timeMultiplier = 1 // Used to extend the listening timer
    int badOFDMs // Tracks lost OFDM packets (not tested)
    int timesTXd // Tracks transmissions since last visit in adapt state
    int reason // Why are we initiating a new round?
    boolean opinionLocked // Ignore new opinions in done state after half time
    volatile int sessionID // Used to tell different rounds apart
    boolean hasReported // Prevent duplicate reports in the same round

    // Design parameters
    int ofdmTime = OFDM_TIME // Millis, also used as time from done to new round
    int minimumListenTime = TIMEOUT // Millis
    int meanExp = EXP_DELAY // Millis, random addition with Exp distro
    float averagingStep = 1.0 // How much of the next opinion should be
    // the compromise
    boolean jitter = true // Use perturbation step as in section 3.1; is
    // ignored in "fine-tuning" mode

    // Statistics
    int timesAdapted // Visits to the adapt state
    long startTime // When we started this round
    long stopTime // When we stopped this round
    int txesThisRound // Times transmitted this round
    int collisionAll // Packet collisions all-time
    int collision // Packet collisions this round
    int badJanusAll // Lost JANUS packets all-time
    int badJanus // Lost JANUS packets this round
    int rxAll // JANUS packets received all-time
    int rx // JANUS packets received this round
    // Below variable enables "fine-tuning" mode when nonzero
    volatile int timesGoneBack // Times heard different opinion after consensus

    // Parameters that should be changeable on the fly
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

    // Also for compatibility with startup script.
    ConsensusAgentWUW(int ofdmTime, int minimumListenTime, int meanExp) {
        setOfdmTime(ofdmTime)
        setMinimumListenTime(minimumListenTime)
        setMeanExp(meanExp)
    }

    // Sanity check attempts to set OFDM timer
    void setOfdmTime(int ofdmTime) {
        if (ofdmTime < 0) {
            log.warning "Set ofdmTime to $ofdmTime failed; can't be negative"
        } else {
            this.ofdmTime = ofdmTime
        }
    }

    // Sanity check attempts to set minimum listen time
    void setMinimumListenTime(int minimumListenTime) {
        if (minimumListenTime < 0) {
            log.warning ("Set minimumListenTime to $minimumListenTime failed;"
            + " can't be negative")
        } else {
            this.minimumListenTime = minimumListenTime
        }
    }

    // Sanity check attempts to set mean of exponentially distributed addition
    void setMeanExp(int meanExp) {
        if (meanExp <= 0) {
            log.warning "Set meanExp to $meanExp failed; must be positive"
        } else {
            this.meanExp = meanExp
        }
    }

    // Get timestamp, with exception handling
    long getTime() {
        try {
            phy.time
        } catch (NullPointerException e) {
            log.warning "Time was null. Process time cannot be found."
            -1L
        }
    }

    // Get max missed packets in a row; default is two (third one restarts)
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
     * Holds this node's confidence in its opinion on parameters. Not tested.
     */
    Map<String, Integer> channelConfidence = new TreeMap<String, Integer>()
    /**
     * The node's state machine behaviour.
     */
    FSMBehavior behaviour

    // Convenient access to the physical agent
    AgentID phy
    
    @Override
    void startup() {
        // Lets us receive notifications about RX'd frames
        subscribeForService(Services.PHYSICAL)
        // Make sure we start with a made-up opinion
        // Future work would ensure that these are better anchored in reality
        makeUpOpinion()
        // Convenient access to the physical layer
        phy = agentForService(Services.PHYSICAL)
        // We use bits 33 downto 26 in the ADB, so we cannot schedule
        if (phy[Physical.JANUS].frameLength != 8) {
            log.severe "The consensus agent does not support cargo. Aborting"
            stop()
            return
        }
        // Only works with a model that finds packet loss from TX power
        // phy[Physical.JANUS].powerLevel = -42
        // Insert a finite state machine behaviour here
        // TODO delay startup until one node gets a start consensus message
        behaviour = add new FSMBehavior()
        // The beginning of the listen state
        behaviour.add new FSMBehavior.State(S_GO) {
            @Override
            void onEnter() {
                log.fine "_STATE_ Entering $S_GO"
                setListenTimer((minimumListenTime + (int) AgentLocalRandom.current()
                .nextExp(1/meanExp))*timeMultiplier)
                timeMultiplier = 1
            }
        }
        // The end of the listen state
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
        // Message state
        behaviour.add new FSMBehavior.State(S_MESSAGE, {
            log.fine "_STATE_ Entering $S_MESSAGE"
            // Send our opinion, then proceed to listen
            sendOpinion()
            log.fine "Opinion $channelOpinion sent. Going back to listening mode"
            behaviour.nextState = S_GO
            // Extra time to account for resetting the timer
            timeMultiplier = 3
        })
        // Adapt state
        behaviour.add new FSMBehavior.State(S_ADAPT, {
            log.fine "_STATE_ Entering $S_ADAPT"
            timesAdapted++
            // Adapt our opinion towards a ``centre of mass''
            if (makeCompromise()) {
                // All opinions were equal, so we reached local consensus
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
        // Done state: we are done here and should be speaking OFDM
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
        // Exiting the done state to start a new round
        behaviour.add new FSMBehavior.State(S_RESTART, {
            // Reset acknowledgement and lost OFDM packets
            log.fine "_STATE_ Entering $S_RESTART"
            // Make sure that we report
            if (reason == NEXT_ROUND) {
                ensureReport()
            }
            // Next session, and reset stats that we just reported
            sessionID++
            opinionLocked = false
            sendNewRound()
            badOFDMs = 0
            timesTXd = 0
            resetStats()
            timesGoneBack = 0
            otherNodes.clear()
            makeUpOpinion()
            behaviour.nextState = S_GO
            startTime = getTime()
        })

        behaviour.setInitialState S_GO
    }

    // Ensures that we have printed statistics and the final opinion.
    void ensureReport() {
        // Do not report the same round more than once
        if (hasReported) return 
        def tdelta = stopTime - startTime
        // Not used at this time, so why even bother?
        // send trace(null, new EndOfConsensusNtf(
        //     stepsTaken: timesAdapted, roundNumber: sessionID,
        //     channelProps: channelOpinion, timeMillis: tdelta/1000)) 
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

    // Sets the listening timer.
    def setListenTimer(int millis) {
        final def currentPacks = otherNodes.size()
        add new WakerBehavior(millis, {
            // No-op unless we did not hear any new nodes since this timer
            // was set
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
            // OFDM parameter selection is future work
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
                // After reporting, we can safely reset our statistics
                resetStats()
            }
        })
        // The OFDM timer itself
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
        // We got a request; check its type
        if (req instanceof StartConsensusReq) {
            // Want to start a new consensus process
            if (behaviour.getCurrentState() != S_DONE) {
                // Cannot start
                return new RefuseRsp(req,
                    "Agent is already in a consensus process")
            }
            try {
                // If the data channel does not support OFDM, this will fail
                phy[USER].modulation = "ofdm"
            } catch (FjageException e) {
                // Catch block keeps the agent from crashing
            }
            log.info("Starting consensus process")
            behaviour.nextState = S_RESTART
            return new Message(recipient: req.sender, inReplyTo: req,
                performative: Performative.AGREE)
        } else if (req instanceof CancelConsensusReq) {
            // Want to abort the consensus process; check if it is sensible
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

        // Randomly select any permitted value
        channelOpinion["doppler"] = (float)rng.nextInt(64)/2.0F
        channelOpinion["delay"] = rng.nextInt(128)
        channelOpinion["noise"] = 32 + rng.nextInt(128)
        // Actually, these are same for all
        // Kept here for future purposes
        channelConfidence["doppler"] = 1
        channelConfidence["delay"] = 1
        channelConfidence["noise"] = 1

        log.fine "Our fabricated channel estimate is $channelOpinion"
    }
    @Override
    void processMessage(Message msg) {
        if (msg instanceof RxJanusFrameNtf
            && msg.classUserID == CUID) {
            // Handle the consensus packet
            switch (msg.appType) {
                case APP_TYPE_PANIC:
                    processPanicNtf(msg)
                case APP_TYPE_CHANNEL:
                    // Count the response towards received packets
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
                // We do not test sending OFDM packets, so this can be ignored
                badOFDMs = Math.max(badOFDMs - 1, 0)
            }
        } else if (msg instanceof BadFrameNtf) {
            if (msg.type == Physical.JANUS) {
                // Missed a JANUS packet
                log.fine "_LOSS_ JANUS packet lost"
                badJanus++
            } else if (behaviour.getCurrentState() == S_DONE
                && msg.type == USER) {
                // We do not test sending OFDM packets
                badOFDMs++
                if (badOFDMs > getMaxRetries()) {
                    // Restart
                    send new StartConsensusReq(recipient: getAgentID())
                }
            }
        } else if (msg instanceof CollisionNtf) {
            // Keep track of packet collision
            log.fine "_COLLIDE_ Packet collision registered"
            collision++
        }
    }

    // Handles a distress packet. Not actively sent.
    void processPanicNtf(Message msg) {
        def appData = msg.appData
        def who = subint(appData, ID_LSB, 8)
        // Future work: field in the ADB that gives a reason for distress,
        // _e.g.,_ low battery, or agent failure
        log.warning("_DOWN_ Node $who has trouble maintaining the consensus "
        + "process, and might have turned off. Discarding it")
        otherNodes.remove(who)
    }

    // Handles a new round packet.
    void processNewRoundNtf(Message msg) {
        def roundNumber = (int) subint(msg.appData, ROUNDNO_LSB, 32)
        def forceBit = subint(msg.appData, FORCE_BIT, 1)
        if (forceBit) {
            // Forcing new round
            send new CancelConsensusReq()
            // Give the agent a chance to abort first
            add new WakerBehavior(1, {
                send new StartConsensusReq(recipient: getAgentID())
            })
        } else {
            // Non-forcing new round
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

    // Ensures that we do not transmit while receiving.
    void sendWhenNotBusy(Message req, int attempt) {
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

    // Sends a channel estimate packet.
    void sendOpinion(int perf) {
        log.fine "_TRACE_ Sending opinion of type $perf"
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
        def from = (int)subint(appData, ID_LSB, 8)
        KEY_IDX.each {
            rxOpinion[it] = antiTransform((int)subint(appData, PARAM_LSB[it],
                WIDTH_LSB[it]), it)
            rxConfidence[it] = 1+subint(appData, RESERVED_LSB, CONF_WIDTH)
        }
        [from, rxOpinion, rxConfidence]
    }

    // Extracts a subset of a long integer. Helper function.
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
                // Compensate for the nonzero minimum value
                result = -32
                if (quantity < 0) {
                    quantity += phy.refPowerLevel
                }
            case "delay":
                result += quantity
                // Delay and noise have the same width, but they should use
                // their own width eventually
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

    // Find the state variable from its formatted representation.
    def antiTransform(int mapped, String key) {
        def result = 0
        switch (key) {
            case "noise":
                // Offset by definition
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

    // Processes a channel state message.
    void processOpinionNtf(Message msg) {
        log.fine "_TRACE_ Processing opinion message $msg"
        // Find type (which has no meaning actually) and who it is from
        def appData = msg.appData
        def performative = (appData & 3 << PERF_LSB) >> PERF_LSB
        def from = (appData & 255 << ID_LSB) >> ID_LSB
        // This switch-case statement is actually redundant
        switch (performative) {
            case OPINION:
            case CONVERGED:
                // Register the opinion if we have not locked our state variables
                if (!opinionLocked) {
                    log.fine "Received opinion from $from. Registering it"
                    registerOpinion(appData)
                }
                break
            default:
                log.warning "Unknown performative $performative"
        }
    }

    // Registers the opinion from a peer, starting from an ADB.
    def registerOpinion(long appData) {
        log.fine ("_TRACE_ Registering opinion from ADB "
            + "${Long.toHexString(appData)}")
        def unbuiltADB = unbuildADB(appData)
        def peerID = unbuiltADB[0]
        def peerOpinion = unbuiltADB[1..<unbuiltADB.size()]
        registerOpinion peerID, peerOpinion
        log.fine("Node $peerID proposes ${peerOpinion[0]} with "
            + "confidence ${peerOpinion[1]}")
        // Fine-tune if we reached local consensus too soon
        if (behaviour.getCurrentState() == S_DONE
                && peerOpinion[0] != channelOpinion) {
            log.info("Heard a different opinion than consensus. Enabling "
                    + "fine-tuning")
            timesGoneBack++
            log.fine("Peer opinion ${peerOpinion[0]} had changed away from"
                + " consensus ${channelOpinion}")
            behaviour.nextState = S_GO
        } else if (behaviour.getCurrentState() == S_GO) {
            // Resets the listen timer
            behaviour.reenterState()
        }
    }

    def registerOpinion(int from, List peerOpinion) {
        otherNodes[from] = [opinion: peerOpinion[0],
            confidence: peerOpinion[1]]
    }

    // Computes the compromise. Also checks and returns whether all received
    // opinions were equal.
    def makeCompromise() {
        log.fine "_TRACE_ Finding compromise and updating to it"
        def knownNodes = otherNodes.keySet()
        def compromiseOpinion = new TreeMap<String, Number>()
        def compromiseConfidence = new TreeMap<String, Integer>()
        def allConsensus = true
        // Handle each state variable sequentially
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
        channelOpinion = moveTowards(compromiseOpinion)
        // Forget all opinions to avoid deadlocks
        otherNodes.clear()
        allConsensus
    }

    // Perturbation step as explained in the reference
    def perturb(String param, Number source) {
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

    // Sets the opinion closer to the compromise it found.
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
                    // Because we are working in half-hertz in Doppler
                    com = roundTowards(2*com, 2*compromise[it], false)/2.0F
                break
                default: 
                    throw new FjageException("Unsupported parameter $it")
            }
            average[it] = com
        }
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
     * This step is user defined. Here, it is the local average.
     */
    def makeCompromise(String key, Map allVotes) {
        def tally = new TreeMap<Number, Integer>()
        // Tally up all votes: list is [value, confidence]
        allVotes.each { dontCare, it ->
            try {
                // log.fine "Registering ${it[0]}: ${it[1]}"
                tally[it[0]] += it[1]
            } catch (NullPointerException e) {
                // Because Groovy does not let you add anything to NULL
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
            // Example strategy: majority vote.
            case "foo":
                tally.each { val, vote ->
                    if (vote > strongestVote) {
                        choice = val
                        strongestVote = vote
                    }
                }
            break
            // Example strategy: local average.
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
                    // If we are fine-tuning, take the floor
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
        // Sixteen is max because we used four bits in an early stage
        strongestVote = Math.min(strongestVote, 16) 
        [opinion: choice, confidence: strongestVote]
    }

    // Resets most statistics, and accumulates some.
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

    // Helpers for rounding.
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

    // Report packet loss ratio at the end.
    void shutdown() {
        super.shutdown()
        log.info("_END_ JANUS packets lost: $badJanusAll/${rxAll+badJanusAll} "
            + "(${badJanusAll/(rxAll + badJanusAll) * 100}%)")
    }

    // Expose our parameters to the end user.
    List<Parameter> getParameterList() {
        allOf DesignParams
    }
}
