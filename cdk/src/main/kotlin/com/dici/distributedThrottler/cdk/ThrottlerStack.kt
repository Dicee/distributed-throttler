package com.dici.distributedThrottler.cdk;

import software.amazon.awscdk.Duration
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.Stack
import software.amazon.awscdk.StackProps
import software.amazon.awscdk.services.ec2.*
import software.amazon.awscdk.services.elasticache.CfnServerlessCache
import software.amazon.awscdk.services.elasticache.CfnServerlessCache.*
import software.amazon.awscdk.services.elasticache.CfnUser
import software.amazon.awscdk.services.elasticache.CfnUserGroup
import software.amazon.awscdk.services.iam.Effect
import software.amazon.awscdk.services.iam.ManagedPolicy
import software.amazon.awscdk.services.iam.PolicyDocument
import software.amazon.awscdk.services.iam.PolicyStatement
import software.amazon.awscdk.services.iam.Role
import software.amazon.awscdk.services.iam.ServicePrincipal
import software.amazon.awscdk.services.lambda.DockerImageCode
import software.amazon.awscdk.services.lambda.DockerImageFunction
import software.amazon.awscdk.services.lambda.Function
import software.constructs.Construct

private const val LAMBDA_USER_ID = "throttler-lambda"

class ThrottlerStack(scope: Construct, props: StackProps? = null) : Stack(scope, "ThrottlerStack", props) {
    init {
        var vpcId = "ThrottlerVpc"
        val vpc = Vpc.Builder.create(this, vpcId)
            .vpcName(vpcId)
            .maxAzs(2)
            .build()

        val valkeyCache = createValkeyCache(vpc)
        createThrottlerLambda(vpc, valkeyCache)
    }

    private fun createValkeyCache(vpc: Vpc): ValkeyCache {
        val baseResourceId = "ValkeyCache"
        val securityGroupId = "${baseResourceId}SecurityGroup"

        val privateSubnetIds = vpc.privateSubnets.map(ISubnet::getSubnetId)
        val securityGroup = SecurityGroup.Builder.create(this, securityGroupId)
            .securityGroupName(securityGroupId)
            .vpc(vpc)
            .description("Security group for ElastiCache Valkey cluster")
            .allowAllOutbound(true)
            .build()

        securityGroup.addIngressRule(securityGroup, Port.tcp(6379), "Allow inbound from self")

        val cacheUsageLimits = CacheUsageLimitsProperty.builder()
            .dataStorage(DataStorageProperty.builder().maximum(1).unit("GB").build())
            .ecpuPerSecond(ECPUPerSecondProperty.builder().maximum(1000).build())
            .build()

        val engine = "valkey"
        val valkeyCacheGroup = createValkeyCacheUserGroup(baseResourceId, engine)

        // https://aws.amazon.com/blogs/database/deploy-amazon-elasticache-for-redis-using-aws-cdk/
        val valkeyCache = CfnServerlessCache.Builder.create(this, baseResourceId)
            .serverlessCacheName("ThrottlerCache")
            .description("Valkey cache used to implement multiple throttling algorithms")
            .engine(engine)
            .majorEngineVersion("8")
            .cacheUsageLimits(cacheUsageLimits)
            .subnetIds(privateSubnetIds)
            .securityGroupIds(listOf(securityGroup.securityGroupId))
            .userGroupId(valkeyCacheGroup.userGroup.userGroupId)
            .build()

        valkeyCache.applyRemovalPolicy(RemovalPolicy.DESTROY) // only because this is a project for fun, would not generally be a good idea in prod
        valkeyCache.addDependency(valkeyCacheGroup.userGroup)

        return ValkeyCache(valkeyCache, valkeyCacheGroup, securityGroup)
    }

    private fun createValkeyCacheUserGroup(baseResourceId: String, engine: String): ValkeyUserGroup {
        val lambdaUser = CfnUser.Builder.create(this, "${baseResourceId}ThrottlerLambdaUser")
            .userName(LAMBDA_USER_ID)
            .userId(LAMBDA_USER_ID)
            .engine(engine)
            .authenticationMode(mapOf("Type" to "iam"))
            // following least-privilege principle, it seems to be the minimum I could give for it to work
            .accessString("on ~* -@all +@hash +@sortedset +pexpire +time +info +evalsha +script|load")
            .build()

        val userGroup = CfnUserGroup.Builder.create(this, "${baseResourceId}UserGroup")
            .userGroupId("valkey-cache-user-group")
            .userIds(listOf(lambdaUser.userId))
            .engine(engine)
            .build()

        userGroup.addDependency(lambdaUser)
        return ValkeyUserGroup(lambdaUser, userGroup)
    }

    private fun createThrottlerLambda(vpc: Vpc, valkeyCache: ValkeyCache): Function {
        val functionName = "ThrottlerLambda"
        val roleName = "${functionName}InvocationRole"

        val role = Role.Builder.create(this, roleName)
            .roleName(roleName)
            .assumedBy(ServicePrincipal("lambda.amazonaws.com"))
            .managedPolicies(listOf(
                ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
            ))
            .inlinePolicies(mapOf(
                "ValkeyCacheAccess" to PolicyDocument.Builder.create()
                    .statements(listOf(PolicyStatement.Builder.create()
                        .effect(Effect.ALLOW)
                        .actions(listOf("elasticache:Connect"))
                        .resources(listOf(
                            valkeyCache.cache.attrArn,
                            valkeyCache.userGroup.lambdaUser.attrArn,
                        ))
                        .build()
                    ))
                    .build()
            ))
            .build()

        val vpcDefaultSecurityGroup = SecurityGroup.fromSecurityGroupId(this, "VpcDefaultSecurityGroup", vpc.vpcDefaultSecurityGroup)
        return DockerImageFunction.Builder.create(this, functionName)
            .functionName(functionName)
            .code(DockerImageCode.fromImageAsset("docker"))
            .role(role)
            .timeout(Duration.seconds(30))
            .memorySize(256)
            .vpc(vpc)
            .vpcSubnets(SubnetSelection.builder().subnets(vpc.privateSubnets).build())
            .securityGroups(listOf(valkeyCache.securityGroup, vpcDefaultSecurityGroup))
            .environment(
                mapOf(
                    "ValkeyCacheName" to valkeyCache.cache.serverlessCacheName.lowercase(),
                    "ValkeyCacheEndpoint" to valkeyCache.cache.attrEndpointAddress,
                    "ValkeyCachePort" to valkeyCache.cache.attrEndpointPort,
                    "ValkeyCacheUserId" to valkeyCache.userGroup.lambdaUser.userId,
                )
            )
            .build()
    }
}

private data class ValkeyUserGroup(val lambdaUser: CfnUser, val userGroup: CfnUserGroup)
private data class ValkeyCache(val cache: CfnServerlessCache, val userGroup: ValkeyUserGroup, val securityGroup: SecurityGroup)