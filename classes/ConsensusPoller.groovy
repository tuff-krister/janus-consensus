//  Agent that checks if the consensus agent is up and running, and restarts
//  it if it perishes.
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
import org.arl.fjage.PoissonBehavior
import org.arl.fjage.TickerBehavior
import org.arl.fjage.WakerBehavior
import org.arl.fjage.param.Parameter
import org.arl.unet.UnetAgent
import org.arl.unet.Services
import org.arl.unet.phy.Physical

class ConsensusPoller extends UnetAgent {
    static final String CONSENSUS_AGENT_ID = "consensus"
    static final String NOISE_LOGGER_ID = "noiser"

    static int ofdmTime = ConsensusAgentWUW.OFDM_TIME
    static int minimumListenTime = ConsensusAgentWUW.TIMEOUT
    static int meanExp = ConsensusAgentWUW.EXP_DELAY
    
    int thisCycle

    List plvls = [-10, -13, -16, -20, -24, -30]
    def phy

    boolean checkRandomly
    int cycleZeroInMillis = 3600*1000
    int checkInterval = 60*1000
    int cycleInterval = 14400*1000

    final String name = "Consensus overwatch"
    final String description = ("Agent that ensures that there is a consensus"
        + " agent up and running on this node.")

    enum Params implements Parameter {
        checkRandomly,
        cycleZeroInMillis,
        checkInterval,
        cycleInterval,
    }

    @Override
    void startup() {
        phy = agentForService(Services.PHYSICAL)
        add new WakerBehavior(cycleZeroInMillis, {
            container.add NOISE_LOGGER_ID, new NoiseLogger()
            pollConsensus()
            log.info "_CYCLE_ Starting cycle 0"
            phy[Physical.JANUS].powerLevel = plvls[thisCycle % plvls.size()]
            if (checkRandomly) {
                add new PoissonBehavior(checkInterval, {
                    pollConsensus()
                })
            } else {
                add new TickerBehavior(checkInterval, {
                    pollConsensus()
                })
            }
        })
        // offset them a little to reduce likelihood of concurrent modification
        add new WakerBehavior(cycleZeroInMillis + 1, {
            add new TickerBehavior(cycleInterval, {
                nextCycle()
            })
        })
    }

    /** Start the next test cycle. Useful for dividing a longer run into
     *  shorter runs while in the field. */
    void nextCycle() {
        def consensus = container.getAgent(new AgentID(CONSENSUS_AGENT_ID))
        if (consensus) {
            consensus.stop()
            log.info "Agent stopped, so it should be up again soon"
        }
        ++thisCycle
        log.info "_CYCLE_ Starting cycle $thisCycle"
        phy[Physical.JANUS].powerLevel = plvls[thisCycle % plvls.size()]
        // let the polling method handle the relaunch
    }

    /** Check if the consensus agent is still up, and respawn it if not. */
    void pollConsensus() {
        def consensus = container.getAgent(new AgentID(CONSENSUS_AGENT_ID))
        if (consensus == null) {
            log.info "Consensus agent not present. Restarting it soon"
            this.add new WakerBehavior((int)(checkInterval/10), {
                log.info "Now restarting the consensus agent"
                container.add CONSENSUS_AGENT_ID, new ConsensusAgentWUW(ofdmTime,
                    minimumListenTime, meanExp)
            })
        }
    }
}
