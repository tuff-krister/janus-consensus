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

class ConsensusPoller extends UnetAgent {
    static final String CONSENSUS_AGENT_ID = "consensus"

    static int ofdmTime = ConsensusAgentWUW.OFDM_TIME
    static int minimumListenTime = ConsensusAgentWUW.TIMEOUT
    static int meanExp = ConsensusAgentWUW.EXP_DELAY
    
    int thisCycle

    boolean checkRandomly
    int checkInterval
    int cycleInterval

    enum Params implements Parameter {
        checkRandomly,
        checkInterval,
        cycleInterval,
    }

    @Override
    void startup() {
        add new WakerBehavior(1000, {
            pollConsensus()
            log.info "_CYCLE_ Starting cycle 0"
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
        add new WakerBehavior(1001, {
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