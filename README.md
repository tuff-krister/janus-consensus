# A JANUS-Based Consensus Protocol for Parametric Modulation Schemes

This repo contains the source files for the [UnetStack](https://unetstack.net)
implementation of the JANUS-based consensus protocol that is the core of
[this paper](https://doi.org/3567600.3568142).
This branch contains the actively maintained version.
The `acm-dl` branch contains the code as it appears in the supplementary
material; please use that one for reproducing any results.

# Simulation considerations

* Use the `DiscreteEventSimulator` for a faster process.
* Load `consensus.groovy` on each and every node in the network that you
  would like to partake in the consensus process.
* Simulate for as long as you wish. If you wish a measure of precision, run
  many shorter simulations by wrapping the `simulate` command in an
  `N.times {}` block, setting `N` to an integer of your choice.
* The power setting has no effect if you do not use a channel model that
  relies on it.
