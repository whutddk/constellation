package constellation.topology

import scala.math.pow
import freechips.rocketchip.config.{Field, Parameters, Config}


import constellation.noc.{NoCKey}
import constellation.channel.{UserIngressParams, UserEgressParams}

class WithTerminalPlane extends Config((site, here, up) => {
  case NoCKey => up(NoCKey).copy(topology=new TerminalPlane(up(NoCKey).topology))
})

class WithTopology(t: PhysicalTopology) extends Config((site, here, up) => {
  case NoCKey => up(NoCKey).copy(topology=t)
})
