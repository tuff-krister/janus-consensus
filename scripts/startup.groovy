phy[3].threshold = 0.25
phy[3].frameLength = 8
container.add 'cpoller', new ConsensusPoller(checkInterval: 60000,
    cycleInterval: 14400000, cycleZeroInMillis: 3600000)
plvl -6
logLevel FINE

