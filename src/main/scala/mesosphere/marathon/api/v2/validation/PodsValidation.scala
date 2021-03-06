package mesosphere.marathon
package api.v2.validation

// scalastyle:off
import java.util.regex.Pattern

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.Features
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ ArgvCommand, Artifact, CommandHealthCheck, Constraint, Endpoint, EnvVarSecretRef, EnvVarValueOrSecret, FixedPodScalingPolicy, HealthCheck, HttpHealthCheck, Image, ImageType, Lifecycle, Network, NetworkMode, Pod, PodContainer, PodPlacementPolicy, PodScalingPolicy, PodSchedulingBackoffStrategy, PodSchedulingPolicy, PodUpgradeStrategy, Resources, SecretDef, ShellCommand, TcpHealthCheck, Volume, VolumeMount }
import mesosphere.marathon.state.{ PathId, ResourceRole }
import mesosphere.marathon.util.SemanticVersion

import scala.collection.immutable.Seq
import scala.util.Try
// scalastyle:on

/**
  * Defines implicit validation for pods
  */
@SuppressWarnings(Array("all")) // wix breaks stuff
trait PodsValidation {
  import Validation._

  val NamePattern = """^[a-z0-9]([-a-z0-9]*[a-z0-9])?$""".r
  val EnvVarNamePattern = """^[A-Z_][A-Z0-9_]*$""".r

  val validName: Validator[String] = validator[String] { name =>
    name should matchRegexWithFailureMessage(
      NamePattern,
      "must contain only alphanumeric chars or hyphens, and must begin with a letter")
    name.length should be > 0
    name.length should be < 64
  }

  val validEnvVarName: Validator[String] = validator[String] { name =>
    name should matchRegexWithFailureMessage(
      EnvVarNamePattern,
      "must contain only alphanumeric chars or underscore, and must not begin with a number")
    name.length should be > 0
    name.length should be < 255
  }

  val networkValidator: Validator[Network] = validator[Network] { network =>
    network.name.each is valid(validName)
  }

  val networksValidator: Validator[Seq[Network]] =
    isTrue[Seq[Network]]("Host networks may not have names or labels") { nets =>
      !nets.filter(_.mode == NetworkMode.Host).exists { n =>
        val hasName = n.name.fold(false){ _.nonEmpty }
        val hasLabels = n.labels.nonEmpty
        hasName || hasLabels
      }
    } and isTrue[Seq[Network]]("Duplicate networks are not allowed") { nets =>
      // unnamed CT nets pick up the default virtual net name
      val unnamedAtMostOnce = nets.count { n => n.name.isEmpty && n.mode == NetworkMode.Container } < 2
      val realNamesAtMostOnce: Boolean = !nets.flatMap(_.name).groupBy(name => name).exists(_._2.size > 1)
      unnamedAtMostOnce && realNamesAtMostOnce
    } and
      isTrue[Seq[Network]]("Must specify either a single host network, or else 1-to-n container networks") { nets =>
        val countsByMode = nets.groupBy { net => net.mode }.map { case (mode, networks) => mode -> networks.size }
        val hostNetworks = countsByMode.getOrElse(NetworkMode.Host, 0)
        val containerNetworks = countsByMode.getOrElse(NetworkMode.Container, 0)
        (hostNetworks == 1 && containerNetworks == 0) || (hostNetworks == 0 && containerNetworks > 0)
      }

  def envValidator(pod: Pod, enabledFeatures: Set[String]) = validator[Map[String, EnvVarValueOrSecret]] { env =>
    env.keys is every(validEnvVarName)

    // if the secrets feature is not enabled then don't allow EnvVarSecretRef's in the environment
    env is isTrue("use of secret-references in the environment requires the secrets feature to be enabled") { env =>
      if (!enabledFeatures.contains(Features.SECRETS))
        env.values.count {
          case _: EnvVarSecretRef => true
          case _ => false
        } == 0
      else true
    }

    env is every(secretRefValidator(pod.secrets))
  }

  val resourceValidator = validator[Resources] { resource =>
    resource.cpus should be >= 0.0
    resource.mem should be >= 0.0
    resource.disk should be >= 0.0
    resource.gpus should be >= 0
  }

  def httpHealthCheckValidator(endpoints: Seq[Endpoint]) = validator[HttpHealthCheck] { hc =>
    hc.endpoint.length is between(1, 63)
    hc.endpoint should matchRegexFully(NamePattern)
    hc.endpoint is isTrue("contained in the container endpoints") { endpoint =>
      endpoints.exists(_.name == endpoint)
    }
    hc.path.map(_.length).getOrElse(1) is between(1, 1024)
  }

  def tcpHealthCheckValidator(endpoints: Seq[Endpoint]) = validator[TcpHealthCheck] { hc =>
    hc.endpoint.length is between(1, 63)
    hc.endpoint should matchRegexFully(NamePattern)
    hc.endpoint is isTrue("contained in the container endpoints") { endpoint =>
      endpoints.exists(_.name == endpoint)
    }
  }

  def commandCheckValidator(mesosMasterVersion: SemanticVersion) = new Validator[CommandHealthCheck] {
    override def apply(v1: CommandHealthCheck): Result = if (mesosMasterVersion >= PodsValidation.MinCommandCheckMesosVersion) {
      v1.command match {
        case ShellCommand(shell) =>
          (shell.length should be > 0)(shell.length)
        case ArgvCommand(argv) =>
          (argv.size should be > 0)(argv.size)
      }
    } else {
      Failure(Set(RuleViolation(v1, s"Mesos Master ($mesosMasterVersion) does not support Command Health Checks", None)))
    }
  }

  def healthCheckValidator(endpoints: Seq[Endpoint], mesosMasterVersion: SemanticVersion) = validator[HealthCheck] { hc =>
    hc.gracePeriodSeconds should be >= 0
    hc.intervalSeconds should be >= 0
    hc.timeoutSeconds should be < hc.intervalSeconds
    hc.maxConsecutiveFailures should be >= 0
    hc.timeoutSeconds should be >= 0
    hc.delaySeconds should be >= 0
    hc.http is optional(httpHealthCheckValidator(endpoints))
    hc.tcp is optional(tcpHealthCheckValidator(endpoints))
    hc.exec is optional(commandCheckValidator(mesosMasterVersion))
    hc is isTrue("Only one of http, tcp, or command may be specified") { hc =>
      Seq(hc.http.isDefined, hc.tcp.isDefined, hc.exec.isDefined).count(identity) == 1
    }
  }

  def endpointValidator(networks: Seq[Network]) = validator[Endpoint] { endpoint =>
    endpoint.name.length is between(1, 63)
    endpoint.name should matchRegexFully(NamePattern)
    endpoint.containerPort.getOrElse(1) is between(1, 65535)
    endpoint.hostPort.getOrElse(0) is between(0, 65535)

    // host-mode networking implies that hostPort is required
    endpoint.hostPort is isTrue("is required when using host-mode networking") { hp =>
      if (networks.exists(_.mode == NetworkMode.Host)) hp.nonEmpty
      else true
    }

    // host-mode networking implies that containerPort is disallowed
    endpoint.containerPort is isTrue("is not allowed when using host-mode networking") { cp =>
      if (networks.exists(_.mode == NetworkMode.Host)) cp.isEmpty
      else true
    }

    // container-mode networking implies that containerPort is required
    endpoint.containerPort is isTrue("is required when using container-mode networking") { cp =>
      if (networks.exists(_.mode == NetworkMode.Container)) cp.nonEmpty
      else true
    }

    // protocol is an optional field, so we really don't need to validate that is empty/non-empty
    // but we should validate that it only contains distinct items
    endpoint.protocol is isTrue ("Duplicate protocols within the same endpoint are not allowed") { proto =>
      proto == proto.distinct
    }
  }

  val imageValidator = validator[Image] { image =>
    image.id.length is between(1, 1024)
  }

  def volumeMountValidator(volumes: Seq[Volume]): Validator[VolumeMount] = validator[VolumeMount] { volumeMount => // linter:ignore:UnusedParameter
    volumeMount.name.length is between(1, 63)
    volumeMount.name should matchRegexFully(NamePattern)
    volumeMount.mountPath.length is between(1, 1024)
    volumeMount.name is isTrue("Referenced Volume in VolumeMount should exist") { name =>
      volumes.exists(_.name == name)
    }
  }

  val artifactValidator = validator[Artifact] { artifact =>
    artifact.uri.length is between(1, 1024)
    artifact.destPath.map(_.length).getOrElse(1) is between(1, 1024)
  }

  val lifeCycleValidator = validator[Lifecycle] { lc =>
    lc.killGracePeriodSeconds.getOrElse(0.0) should be > 0.0
  }

  def containerValidator(pod: Pod, enabledFeatures: Set[String], mesosMasterVersion: SemanticVersion): Validator[PodContainer] =
    validator[PodContainer] { container =>
      container.resources is valid(resourceValidator)
      container.endpoints is every(endpointValidator(pod.networks))
      container.image.getOrElse(Image(ImageType.Docker, "abc")) is valid(imageValidator)
      container.environment is envValidator(pod, enabledFeatures)
      container.healthCheck is optional(healthCheckValidator(container.endpoints, mesosMasterVersion))
      container.volumeMounts is every(volumeMountValidator(pod.volumes))
      container.artifacts is every(artifactValidator)
    }

  def volumeValidator(containers: Seq[PodContainer]): Validator[Volume] = validator[Volume] { volume =>
    volume.name is valid(validName)
    volume.host is optional(notEmpty)
  } and isTrue[Volume]("volume must be referenced by at least one container") { v =>
    containers.exists(_.volumeMounts.exists(_.name == v.name))
  }

  val backoffStrategyValidator = validator[PodSchedulingBackoffStrategy] { bs =>
    bs.backoff should be >= 0.0
    bs.backoffFactor should be >= 0.0
    bs.maxLaunchDelay should be >= 0.0
  }

  val upgradeStrategyValidator = validator[PodUpgradeStrategy] { us =>
    us.maximumOverCapacity should be >= 0.0
    us.maximumOverCapacity should be <= 1.0
    us.minimumHealthCapacity should be >= 0.0
    us.minimumHealthCapacity should be <= 1.0
  }

  val secretValidator = validator[Map[String, SecretDef]] { s =>
    s.keys is every(notEmpty)
    s.values.map(_.source) as "source" is every(notEmpty)
  }

  val complyWithConstraintRules: Validator[Constraint] = new Validator[Constraint] {
    import mesosphere.marathon.raml.ConstraintOperator._
    override def apply(c: Constraint): Result = {
      if (c.fieldName.isEmpty) {
        Failure(Set(RuleViolation(c, "Missing field and operator", None)))
      } else {
        c.operator match {
          case Unique =>
            c.value.fold[Result](Success) { _ => Failure(Set(RuleViolation(c, "Value specified but not used", None))) }
          case Cluster =>
            if (c.value.isEmpty || c.value.map(_.length).getOrElse(0) == 0) {
              Failure(Set(RuleViolation(c, "Missing value", None)))
            } else {
              Success
            }
          case GroupBy =>
            if (c.value.fold(true)(i => Try(i.toInt).isSuccess)) {
              Success
            } else {
              Failure(Set(RuleViolation(
                c,
                "Value was specified but is not a number",
                Some("GROUP_BY may either have no value or an integer value"))))
            }
          case MaxPer =>
            if (c.value.fold(false)(i => Try(i.toInt).isSuccess)) {
              Success
            } else {
              Failure(Set(RuleViolation(
                c,
                "Value was not specified or is not a number",
                Some("MAX_PER must have an integer value"))))
            }
          case Like | Unlike =>
            c.value.fold[Result] {
              Failure(Set(RuleViolation(c, "A regular expression value must be provided", None)))
            } { p =>
              Try(Pattern.compile(p)) match {
                case util.Success(_) => Success
                case util.Failure(e) =>
                  Failure(Set(RuleViolation(
                    c,
                    s"'$p' is not a valid regular expression",
                    Some(s"$p\n${e.getMessage}"))))
              }
            }
        }
      }
    }
  }

  val placementStrategyValidator = validator[PodPlacementPolicy] { ppp =>
    ppp.acceptedResourceRoles.toSet is empty or ResourceRole.validAcceptedResourceRoles(false)
    ppp.constraints is empty or every(complyWithConstraintRules)
  }

  val schedulingValidator = validator[PodSchedulingPolicy] { psp =>
    psp.backoff is optional(backoffStrategyValidator)
    psp.upgrade is optional(upgradeStrategyValidator)
    psp.placement is optional(placementStrategyValidator)
  }

  val fixedPodScalingPolicyValidator = validator[FixedPodScalingPolicy] { f =>
    f.instances should be >= 0
  }

  val scalingValidator: Validator[PodScalingPolicy] = new Validator[PodScalingPolicy] {
    override def apply(v1: PodScalingPolicy): Result = v1 match {
      case fsf: FixedPodScalingPolicy => fixedPodScalingPolicyValidator(fsf)
      case _ => Failure(Set(RuleViolation(v1, "Not a fixed scaling policy", None)))
    }
  }

  def secretRefValidator(secrets: Map[String, SecretDef]) = validator[(String, EnvVarValueOrSecret)] { entry =>
    entry._2 as s"${entry._1}" is isTrue("references an undefined secret"){
      case ref: EnvVarSecretRef => secrets.contains(ref.secret)
      case _ => true
    }
  }

  val endpointNamesUnique: Validator[Pod] = isTrue("Endpoint names are unique") { pod: Pod =>
    val names = pod.containers.flatMap(_.endpoints.map(_.name))
    names.distinct.size == names.size
  }

  val endpointContainerPortsUnique: Validator[Pod] = isTrue("Container ports are unique") { pod: Pod =>
    val containerPorts = pod.containers.flatMap(_.endpoints.flatMap(_.containerPort))
    containerPorts.distinct.size == containerPorts.size
  }

  val endpointHostPortsUnique: Validator[Pod] = isTrue("Host ports are unique") { pod: Pod =>
    val hostPorts = pod.containers.flatMap(_.endpoints.flatMap(_.hostPort)).filter(_ != 0)
    hostPorts.distinct.size == hostPorts.size
  }

  def podDefValidator(enabledFeatures: Set[String], mesosMasterVersion: SemanticVersion): Validator[Pod] = validator[Pod] { pod =>
    PathId(pod.id) as "id" is valid and PathId.absolutePathValidator and PathId.nonEmptyPath
    pod.user is optional(notEmpty)
    pod.environment is envValidator(pod, enabledFeatures)
    pod.volumes is every(volumeValidator(pod.containers)) and isTrue("volume names are unique") { volumes: Seq[Volume] =>
      val names = volumes.map(_.name)
      names.distinct.size == names.size
    }
    pod.containers is notEmpty and every(containerValidator(pod, enabledFeatures, mesosMasterVersion))
    pod.containers is isTrue("container names are unique") { containers: Seq[PodContainer] =>
      val names = pod.containers.map(_.name)
      names.distinct.size == names.size
    }
    pod.secrets is empty or (valid(secretValidator) and featureEnabled(enabledFeatures, Features.SECRETS))
    pod.networks is valid(networksValidator)
    pod.networks is every(networkValidator)
    pod.scheduling is optional(schedulingValidator)
    pod.scaling is optional(scalingValidator)
    pod is endpointNamesUnique and endpointContainerPortsUnique and endpointHostPortsUnique
  }
}

object PodsValidation extends PodsValidation {
  // TODO: Change this value when mesos supports command checks for pods.
  val MinCommandCheckMesosVersion = SemanticVersion(Int.MaxValue, Int.MaxValue, Int.MaxValue)
}
