import org.arl.unet.UnetAgent
import org.arl.unet.Services
import org.arl.fjage.TickerBehavior
import org.arl.fjage.param.Parameter

class NoiseLogger extends UnetAgent {
    int interval = 10000
    def phy

    final String name = "Noise logger"
    final String description = "Agent that just reads noise level at a given interval."

    enum Params implements Parameter {
        interval
    }

    void startup() {
        phy = agentForService(Services.PHYSICAL)
        add new TickerBehavior(interval, {
            log.fine String.format("_NOISE_ Noise measured to %.2f dB", phy.noise)
        })
    }
}
