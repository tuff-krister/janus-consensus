# A JANUS-Based Consensus Protocol for Parametric Modulation Schemes

This branch contains the source files for the [UnetStack](https://unetstack.net)
implementation of the JANUS-based consensus protocol as they appear in the
Association for Computing Machinery's Digital Library.
If you cloned this branch yourself, the paper can be found in
[the ACM library](https://doi.org/10.1145/3567600.3568142).

# Simulation considerations

* Use the `DiscreteEventSimulator`.
* Load `consensus.groovy` on each and every node in the network that you
  would like to partake in the consensus process.
* Simulate for as long as you wish. I recommend that you simulate for
  at least five days in total.
* If you wish a measure of precision, run many shorter simulations by
  wrapping the `simulate` command in an `N.times {}` block, setting `N` to
  an integer of your choice.
* The power setting has no effect if you do not use a channel model that
  relies on it.
