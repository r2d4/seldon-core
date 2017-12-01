package io.seldon.clustermanager.k8s;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeploymentUtils {

    private final static Logger logger = LoggerFactory.getLogger(DeploymentUtils.class);

    /*
    public static class ServiceSelectorDetails {
        public final String appLabelName = "seldon-app";
        public final String appLabelValue;
        public final String trackLabelName = "seldon-track";
        public final String trackLabelValue;
        public final boolean serviceNeeded;
        public final static String seldonLabelName = "seldon-type";
        public final static String seldonLabelMlDepValue = "mldeployment";
        

        public ServiceSelectorDetails(String seldonDeploymentId, boolean isCanary) {
            //@formatter:off
            this.appLabelValue = getKubernetesDeploymentId(seldonDeploymentId, false); // Force selector to use the main predictor
            //@formatter:on
            this.trackLabelValue = isCanary ? "canary" : "stable";
            this.serviceNeeded = !isCanary;
        }

        @Override
        public String toString() {
            return ReflectionToStringBuilder.toString(this, ToStringStyle.SHORT_PREFIX_STYLE);
        }
    }

    public static class BuildDeploymentResult {
        public final Deployment deployment;
        public final Optional<Service> service;
        public final PredictorDef resultingPredictorDef;
        public final boolean isCanary;

        public BuildDeploymentResult(Deployment deployment, Optional<Service> service, PredictorDef resultingPredictorDef, boolean isCanary) {
            this.deployment = deployment;
            this.service = service;
            this.resultingPredictorDef = resultingPredictorDef;
            this.isCanary = isCanary;
        }

        @Override
        public String toString() {
            return ReflectionToStringBuilder.toString(this, ToStringStyle.SHORT_PREFIX_STYLE);
        }

    }

    public static List<BuildDeploymentResult> buildDeployments(SeldonDeployment mldeployment, ClusterManagerProperites clusterManagerProperites) {

    	final DeploymentSpec deploymentDef = mldeployment.getSpec();
        final String seldonDeploymentId = deploymentDef.getId();
        List<BuildDeploymentResult> buildDeploymentResults = new ArrayList<>();

        
        final OwnerReference oref = new OwnerReference(
				mldeployment.getApiVersion(), 
				true, 
				mldeployment.getKind(), 
				mldeployment.getMetadata().getName(), 
				mldeployment.getMetadata().getUid());
        
        { // Add the main predictor
            PredictorSpec mainPredictor = deploymentDef.getPredictor();
            boolean isCanary = false;
            BuildDeploymentResult buildDeploymentResult = buildDeployment(seldonDeploymentId, mainPredictor, isCanary, clusterManagerProperites,oref);
            buildDeploymentResults.add(buildDeploymentResult);
        }

        { // Add the canary predictor if it exists
            if (deploymentDef.hasField(deploymentDef.getDescriptorForType().findFieldByNumber(DeploymentDef.PREDICTOR_CANARY_FIELD_NUMBER))) {
                PredictorDef canaryPredictor = deploymentDef.getPredictorCanary();
                boolean isCanary = true;
                BuildDeploymentResult buildDeploymentResult = buildDeployment(seldonDeploymentId, canaryPredictor, isCanary, clusterManagerProperites,oref);
                buildDeploymentResults.add(buildDeploymentResult);
            }
        }

        return buildDeploymentResults;
    }

    public static BuildDeploymentResult buildDeployment(String seldonDeploymentId, PredictorDef predictorDef, boolean isCanary,
            ClusterManagerProperites clusterManagerProperites,OwnerReference oref) {

    	// Create owner reference list if exists
    	List<OwnerReference> orefList = null;
        if (oref != null)
        {
        	orefList = new ArrayList<>();
        	orefList.add(oref);
        }
        PredictorDef.Builder resultingPredictorDefBuilder = PredictorDef.newBuilder(predictorDef);

        final EndpointType ENGINE_CONTAINER_ENDPOINT_TYPE = EndpointDef.EndpointType.REST;
        final int ENGINE_CONTAINER_PORT = clusterManagerProperites.getEngineContainerPort();
        final String ENGINE_CONTAINER_IMAGE_AND_VERSION = clusterManagerProperites.getEngineContainerImageAndVersion();
        final int PU_CONTAINER_PORT_BASE = clusterManagerProperites.getPuContainerPortBase();

        List<Container> containers = new ArrayList<>();
        List<Service> services = new ArrayList<>();

        final String kubernetesDeploymentId = getKubernetesDeploymentId(seldonDeploymentId, isCanary);

        List<PredictiveUnitDef> predictiveUnits = predictorDef.getPredictiveUnitsList();
        int predictiveUnitIndex = -1;
        for (PredictiveUnitDef predictiveUnitDef : predictiveUnits) {
            predictiveUnitIndex++;

            if (!isContainerRequired(predictiveUnitDef)) {
                logger.debug("IGNORE provision for container of predictiveUnit name[{}] type[{}] subtype[{}]", predictiveUnitDef.getName(),
                        predictiveUnitDef.getType(), predictiveUnitDef.getSubtype());
                continue; // only create container details for predictiveUnit that need it (eg. subtype is external)
            }

            final ClusterResourcesDef clusterResourcesDef = predictiveUnitDef.getClusterResources();

            final int container_port = PU_CONTAINER_PORT_BASE + predictiveUnitIndex;
            final int service_port = container_port;


            final String predictiveUnitParameters = extractPredictiveUnitParametersAsJson(predictiveUnitDef);

            final String image_name_and_version = (clusterResourcesDef.getVersion().length() > 0)
                    ? clusterResourcesDef.getImage() + ":" + clusterResourcesDef.getVersion() : clusterResourcesDef.getImage();

            EnvVar envVar_PREDICTIVE_UNIT_PARAMETERS = new EnvVarBuilder().withName("PREDICTIVE_UNIT_PARAMETERS").withValue(predictiveUnitParameters).build();
            EnvVar envVar_PREDICTIVE_UNIT_SERVICE_PORT = new EnvVarBuilder().withName("PREDICTIVE_UNIT_SERVICE_PORT").withValue(String.valueOf(container_port))
                    .build();
            EnvVar envVar_PREDICTIVE_UNIT_ID = new EnvVarBuilder().withName("PREDICTIVE_UNIT_ID").withValue(predictiveUnitDef.getId()).build();
            EnvVar envVar_SELDON_DEPLOYMENT_ID = new EnvVarBuilder().withName("SELDON_DEPLOYMENT_ID").withValue(seldonDeploymentId).build();

            Map<String, Quantity> resource_requests = new HashMap<>();
            { // Add container resource requests
                if (clusterResourcesDef.hasField(clusterResourcesDef.getDescriptorForType().findFieldByNumber(ClusterResourcesDef.CPU_FIELD_NUMBER)) &&
                		StringUtils.isNotEmpty(clusterResourcesDef.getCpu())) {
                    resource_requests.put("cpu", new Quantity(clusterResourcesDef.getCpu()));
                }
                if (clusterResourcesDef.hasField(clusterResourcesDef.getDescriptorForType().findFieldByNumber(ClusterResourcesDef.MEMORY_FIELD_NUMBER)) &&
                		StringUtils.isNotEmpty(clusterResourcesDef.getMemory())) {
                    resource_requests.put("memory", new Quantity(clusterResourcesDef.getMemory()));
                }
            }

            //@formatter:off
            Container c = new ContainerBuilder()
                    .withName("seldon-container-pu-"+String.valueOf(predictiveUnitIndex)).withImage(image_name_and_version)
                    .withEnv(envVar_PREDICTIVE_UNIT_PARAMETERS, envVar_PREDICTIVE_UNIT_SERVICE_PORT, envVar_PREDICTIVE_UNIT_ID, envVar_SELDON_DEPLOYMENT_ID)
                    .addNewPort().withContainerPort(container_port).endPort()
                    .withNewResources()
                        .addToRequests(resource_requests)
                    .endResources()
                    .withNewReadinessProbe()
                		.withNewTcpSocket().withNewPort(container_port).endTcpSocket()
                		.withInitialDelaySeconds(10)
                		.withPeriodSeconds(5)
                	.endReadinessProbe()
                    .withNewLivenessProbe()
                		.withNewTcpSocket().withNewPort(container_port).endTcpSocket()
                		.withInitialDelaySeconds(10)
                		.withPeriodSeconds(5)
                	.endLivenessProbe()
                	.withNewLifecycle()
            		.withNewPreStop()
            			.withNewExec()
            				.withCommand("/bin/bash","-c","/bin/sleep 20")
            			.endExec()
            			.endPreStop()
            		.endLifecycle()
                    .build();
            
            containers.add(c);
            //@formatter:on
            logger.debug("ADDING provision for container of predictiveUnit name[{}] type[{}] subtype[{}] image[{}]", predictiveUnitDef.getName(),
                    predictiveUnitDef.getType(), predictiveUnitDef.getSubtype(), image_name_and_version);

            { // update the resulting predictorDef with the host/port details for this predictive
                resultingPredictorDefBuilder.getPredictiveUnitsBuilder(predictiveUnitIndex).getEndpointBuilder().setServiceHost("localhost");
                resultingPredictorDefBuilder.getPredictiveUnitsBuilder(predictiveUnitIndex).getEndpointBuilder().setServicePort(service_port);
            }
        }

        final int replica_number = predictorDef.getReplicas();

        List<LocalObjectReference> imagePullSecrets = new ArrayList<>();
        { // add any image pull secrets
            Consumer<String> p = (x) -> {
                LocalObjectReference imagePullSecretObject = new LocalObjectReference(x);
                imagePullSecrets.add(imagePullSecretObject);
            };
            predictorDef.getImagePullSecretsList().forEach(p);
        }

        final int engine_container_port = ENGINE_CONTAINER_PORT;
        final int engine_service_port = engine_container_port;
        final int management_port = 8082;
        
        if (clusterManagerProperites.isIstioEnabled())
        {
        	List<String> args = new ArrayList<>();
        	args.add("proxy");
            args.add("sidecar");
            args.add("-v");
            args.add("2");
            args.add("--passthrough");
            args.add(""+ENGINE_CONTAINER_PORT);
           
            EnvVar envar_pod_name = new EnvVarBuilder().withName("POD_NAME")
            			.withNewValueFrom()
            				.withNewFieldRef()
            					.withApiVersion("v1")
            					.withFieldPath("metadata.name")
            				.endFieldRef()
            			.endValueFrom()
            			.build();
            
            EnvVar envar_pod_namespace = new EnvVarBuilder().withName("POD_NAMESPACE")
        			.withNewValueFrom()
        				.withNewFieldRef()
        					.withApiVersion("v1")
        					.withFieldPath("metadata.namespace")
        				.endFieldRef()
        			.endValueFrom()
        			.build();

            EnvVar envar_pod_ip = new EnvVarBuilder().withName("POD_IP")
        			.withNewValueFrom()
        				.withNewFieldRef()
        					.withApiVersion("v1")
        					.withFieldPath("status.podIP")
        				.endFieldRef()
        			.endValueFrom()
        			.build();
            //@formatter:off
        	Container c = new ContainerBuilder()
        			 .withName("proxy")
        			 .withImage("docker.io/istio/proxy_debug:0.1")
        			 .withNewSecurityContext()
        			 	.withRunAsUser(1337L)
        			 .endSecurityContext()
        			 .withArgs(args)
        			 .withEnv(envar_pod_name, envar_pod_namespace, envar_pod_ip)
                 	.withNewLifecycle()
             		.withNewPreStop()
             			.withNewExec()
             				.withCommand("/bin/bash","-c","/bin/sleep 20")
             			.endExec()
             			.endPreStop()
             		.endLifecycle()

        			 .build();
        	 containers.add(c);
             //@formatter:on
        	 logger.debug("ADDING istio container");
        }
        
        { // add container for engine
            final String image_name_and_version = ENGINE_CONTAINER_IMAGE_AND_VERSION;

            String enginePredictorJson = getEnginePredictorEnvVarJson(resultingPredictorDefBuilder.build());
            EnvVar envVar_ENGINE_PREDICTOR = new EnvVarBuilder().withName("ENGINE_PREDICTOR").withValue(enginePredictorJson).build();
            EnvVar envVar_ENGINE_SERVER_PORT = new EnvVarBuilder().withName("ENGINE_SERVER_PORT").withValue(String.valueOf(engine_container_port)).build();

            //@formatter:off
            Container c = new ContainerBuilder()
                    .withName("seldon-container-engine").withImage(image_name_and_version)
                    .withEnv(envVar_ENGINE_PREDICTOR, envVar_ENGINE_SERVER_PORT)
                    .addNewPort().withContainerPort(engine_container_port).endPort()
                    .withNewReadinessProbe()
                    	.withHttpGet(new HTTPGetActionBuilder().withNewPort(management_port).withPath("/ready").build())
                    	.withInitialDelaySeconds(20)
                    	.withPeriodSeconds(5)
                    	.withFailureThreshold(1)
                    	.withSuccessThreshold(1)
                    	.withTimeoutSeconds(2)
                    .endReadinessProbe()
                    .withNewLivenessProbe()
                		.withHttpGet(new HTTPGetActionBuilder().withNewPort(management_port).withPath("/ping").build())
                		.withInitialDelaySeconds(20)
                		.withFailureThreshold(1)
                    	.withSuccessThreshold(1)
                		.withPeriodSeconds(5)
                		.withTimeoutSeconds(2)
                	.endLivenessProbe()
                	.withNewLifecycle()
            		.withNewPreStop()
            			.withNewExec()
            				.withCommand("/bin/bash","-c","curl 127.0.0.1:"+engine_container_port+"/pause && /bin/sleep 20")
            			.endExec()
            		.endPreStop()
            	.endLifecycle()
                    .build();
            
            containers.add(c);
            //@formatter:on
            logger.debug("ADDING provision for container of seldon engine");
        }

        ServiceSelectorDetails serviceSelectorDetails = new ServiceSelectorDetails(seldonDeploymentId, isCanary);
        Service service = null;
        if (serviceSelectorDetails.serviceNeeded) { // build service for this predictor
            final String deploymentName = kubernetesDeploymentId;
            String serviceName = deploymentName;

            String selectorName = serviceSelectorDetails.appLabelName;
            String selectorValue = serviceSelectorDetails.appLabelValue;

            int port = engine_service_port;
            int targetPort = engine_container_port;

            
            //@formatter:off
            service = new ServiceBuilder()
                    .withNewMetadata()
                        .withName(serviceName)
                        .addToLabels("seldon-deployment-id", seldonDeploymentId)
                        .addToLabels("app", selectorValue)
                        .withOwnerReferences(orefList)
                    .endMetadata()
                    .withNewSpec()
                        .addNewPort()
                            .withProtocol("TCP")
                            .withPort(port)
                            .withNewTargetPort(targetPort)
                            .withName("http")
                        .endPort()
                        .addToSelector(selectorName, selectorValue)
                        .withType("ClusterIP")
                    .endSpec()
                    .build();
            //@formatter:on
            services.add(service);

            //@formatter:off
            resultingPredictorDefBuilder.setEndpoint(EndpointDef.newBuilder()
                    .setServiceHost(serviceName)
                    .setServicePort(port)
                    .setType(ENGINE_CONTAINER_ENDPOINT_TYPE)
                    .build());
            //@formatter:on
        }

        //@formatter:off
        Deployment deployment = new DeploymentBuilder()
            .withNewMetadata()
            	.withName(kubernetesDeploymentId)
            	.addToLabels(serviceSelectorDetails.appLabelName, serviceSelectorDetails.appLabelValue)            	
            	.addToLabels("seldon-deployment-id", seldonDeploymentId)
            	.addToLabels("app", serviceSelectorDetails.appLabelValue)            	
            	.addToLabels("version", predictorDef.getVersion())                    	            	
            	.addToLabels(ServiceSelectorDetails.seldonLabelName, ServiceSelectorDetails.seldonLabelMlDepValue)
            	.withOwnerReferences(orefList)
            .endMetadata()
            .withNewSpec().withReplicas(replica_number)
                .withNewTemplate()
                    .withNewMetadata()
                	.addToLabels(serviceSelectorDetails.appLabelName, serviceSelectorDetails.appLabelValue)
                    	.addToLabels("app", serviceSelectorDetails.appLabelValue)
                    	.addToLabels("version", predictorDef.getVersion())                    	
                        .addToLabels(serviceSelectorDetails.trackLabelName, serviceSelectorDetails.trackLabelValue)
                        .withAnnotations(getDeploymentAnnotations(clusterManagerProperites,engine_container_port))
                    .endMetadata()
                    .withNewSpec()
                        .addAllToContainers(containers)
                        .addAllToImagePullSecrets(imagePullSecrets)
                    .endSpec()
                .endTemplate()
                .withNewStrategy()
                	.withNewRollingUpdate()
                		.withNewMaxUnavailable("10%")
                	.endRollingUpdate()
                .endStrategy()
            .endSpec().build();
        //@formatter:on

        BuildDeploymentResult buildDeploymentResult = new BuildDeploymentResult(deployment, Optional.ofNullable(service), resultingPredictorDefBuilder.build(),
                isCanary);
        return buildDeploymentResult;
    }
    
    private static Map<String,String> getDeploymentAnnotations(ClusterManagerProperites clusterManagerProperites,int engine_container_port)
    {
		Map<String,String> props = new HashMap<>();
		props.put("prometheus.io/path", "/prometheus");
		props.put("prometheus.io/port", ""+ engine_container_port);
		props.put("prometheus.io/scrape", "true");
		
    	if (clusterManagerProperites.isIstioEnabled())
    	{
    		logger.debug("ADDING istio annotations");
    		props.put("alpha.istio.io/sidecar", "injected");
    		props.put( "alpha.istio.io/version","jenkins@ubuntu-16-04-build-12ac793f80be71-0.1.6-dab2033");
    		props.put("pod.alpha.kubernetes.io/init-containers", "[{\"name\":\"init\",\"image\":\"docker.io/istio/init:0.1\",\"args\":[\"-p\",\"15001\",\"-u\",\"1337\"],\"resources\":{},\"terminationMessagePath\":\"/dev/termination-log\",\"terminationMessagePolicy\":\"File\",\"imagePullPolicy\":\"Always\",\"securityContext\":{\"capabilities\":{\"add\":[\"NET_ADMIN\"]}}},{\"name\":\"enable-core-dump\",\"image\":\"alpine\",\"command\":[\"/bin/sh\"],\"args\":[\"-c\",\"sysctl -w kernel.core_pattern=/tmp/core.%e.%p.%t \\u0026\\u0026 ulimit -c unlimited\"],\"resources\":{},\"terminationMessagePath\":\"/dev/termination-log\",\"terminationMessagePolicy\":\"File\",\"imagePullPolicy\":\"Always\",\"securityContext\":{\"privileged\":true}}]");
    		props.put("pod.beta.kubernetes.io/init-containers", "[{\"name\":\"init\",\"image\":\"docker.io/istio/init:0.1\",\"args\":[\"-p\",\"15001\",\"-u\",\"1337\"],\"resources\":{},\"terminationMessagePath\":\"/dev/termination-log\",\"terminationMessagePolicy\":\"File\",\"imagePullPolicy\":\"Always\",\"securityContext\":{\"capabilities\":{\"add\":[\"NET_ADMIN\"]}}},{\"name\":\"enable-core-dump\",\"image\":\"alpine\",\"command\":[\"/bin/sh\"],\"args\":[\"-c\",\"sysctl -w kernel.core_pattern=/tmp/core.%e.%p.%t \\u0026\\u0026 ulimit -c unlimited\"],\"resources\":{},\"terminationMessagePath\":\"/dev/termination-log\",\"terminationMessagePolicy\":\"File\",\"imagePullPolicy\":\"Always\",\"securityContext\":{\"privileged\":true}}]");
    	}
    	return props;
    }

    public static void createDeployment(KubernetesClient kubernetesClient, String namespace_name, BuildDeploymentResult buildDeploymentResult) {
        Deployment deployment = kubernetesClient.extensions().deployments().inNamespace(namespace_name).createOrReplace(buildDeploymentResult.deployment);
        String deploymentName = (deployment != null) ? deployment.getMetadata().getName() : "null";
        logger.debug(String.format("Created kubernetes delployment [%s]", deploymentName));
        if ((deployment != null) && (buildDeploymentResult.service.isPresent())) {
        	String serviceName = buildDeploymentResult.service.get().getMetadata().getName();
        	logger.debug(String.format("Looking for kubernetes service [%s]", serviceName));
        	Service service = kubernetesClient.services().inNamespace(namespace_name).withName(serviceName).get();
        	if (service == null)
        	{
        		service = kubernetesClient.services().inNamespace(namespace_name).createOrReplace(buildDeploymentResult.service.get());
        		serviceName = (service != null) ? service.getMetadata().getName() : "null";
        		logger.debug(String.format("Created kubernetes service [%s]", serviceName));
        	}
        	else
        		logger.debug(String.format("kubernetes service [%s] already exists. Not updating", serviceName));
        }
    }

    public static void deleteDeployment(KubernetesClient kubernetesClient, String namespace_name, DeploymentDef deploymentDef) {
        final String seldonDeploymentId = deploymentDef.getId();

        { // delete the services for this seldon deployment

            io.fabric8.kubernetes.api.model.ServiceList svcList = kubernetesClient.services().inNamespace(namespace_name)
                    .withLabel("seldon-deployment-id", seldonDeploymentId).list();
            for (io.fabric8.kubernetes.api.model.Service service : svcList.getItems()) {
                kubernetesClient.resource(service).inNamespace(namespace_name).delete();
                String rsmsg = (service != null) ? service.getMetadata().getName() : null;
                logger.debug(String.format("Deleted kubernetes service [%s]", rsmsg));
            }
        }

        //@formatter:off
        deleteDeployemntResources(kubernetesClient, namespace_name, seldonDeploymentId, false); // for the main predictor
        deleteDeployemntResources(kubernetesClient, namespace_name, seldonDeploymentId, true); // for the canary
        //@formatter:on
    }

    public static void deleteDeployemntResources(KubernetesClient kubernetesClient, String namespace_name, String seldonDeploymentId, boolean isCanary) {
        final String kubernetesDeploymentId = getKubernetesDeploymentId(seldonDeploymentId, isCanary);
        final String deploymentName = kubernetesDeploymentId;
        boolean wasDeleted = kubernetesClient.extensions().deployments().inNamespace(namespace_name).withName(deploymentName).delete();
        if (wasDeleted) {
            logger.debug(String.format("Deleted kubernetes delployment [%s]", deploymentName));
        }
        io.fabric8.kubernetes.api.model.extensions.ReplicaSetList rslist = kubernetesClient.extensions().replicaSets().inNamespace(namespace_name)
                .withLabel("seldon-app", deploymentName).list();
        for (io.fabric8.kubernetes.api.model.extensions.ReplicaSet rs : rslist.getItems()) {
            kubernetesClient.resource(rs).inNamespace(namespace_name).delete();
            String rsmsg = (rs != null) ? rs.getMetadata().getName() : null;
            logger.debug(String.format("Deleted kubernetes replicaSet [%s]", rsmsg));
        }

    }

    private static String extractPredictiveUnitParametersAsJson(PredictiveUnitDef predictiveUnitDef) {
        StringJoiner sj = new StringJoiner(",", "[", "]");
        List<ParamDef> parameters = predictiveUnitDef.getParametersList();
        for (ParamDef parameter : parameters) {
            try {
                String j = ProtoBufUtils.toJson(parameter, true);
                sj.add(j);
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }
        return sj.toString();
    }

    private static boolean isContainerRequired(PredictiveUnitDef predictiveUnitDef) {
        // Predictive units that have the subtype "microservice" are the only ones that need a container to be provisioned
        return predictiveUnitDef.getSubtype().equals(PredictiveUnitSubType.MICROSERVICE);
    }

    private static String getKubernetesDeploymentId(String seldonDeploymentId, boolean isCanary) {
        return "sd-" + seldonDeploymentId + "-" + ((isCanary) ? "c" : "p");
    }

    public static String getEnginePredictorEnvVarJson(PredictorDef predictorDef) {
        String retVal;
        try {
            retVal = ProtoBufUtils.toJson(predictorDef, true);
        } catch (InvalidProtocolBufferException e) {
            retVal = e.getMessage();
        }

        retVal = new String(Base64.getEncoder().encode(retVal.getBytes()));

        return retVal;
    }
    */
}