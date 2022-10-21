# A JANUS-Based Consensus Protocol for Parametric Modulation Schemes

This repo contains the source files for the [UnetStack](https://unetstack.net)
implementation of my envisioned JANUS-based consensus protocol.
The consensus agent is the core of the paper with the same name as the
section header. The GitHub repo contains the up-to-date version of it.
The ACM_DL branch contains the code as it appears in the supplementary
material.

# Simulation considerations

* Use the `DiscreteEventSimulator` for a faster process.
* Load `consensus.groovy` on each and every node in the network that you
  would like to partake in the consensus process.
* Simulate for as long as you wish. If you wish a measure of precision, run
  many shorter simulations by wrapping the `simulate` command in an
  `N.times {}` block, setting `N` to an integer of your choice.
* The power setting has no effect if you do not use a channel model that
  relies on it.
