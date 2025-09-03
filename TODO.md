## TODO

### Basic set up

- At the end of the project, make sure WSL doesn't start a local Redis server every time it boots. I prefer starting it manually.

### Minor improvements

- consider using coroutines, in particular for the leaky bucket implementations (though to do it well it's likely going to force moving everything to suspendable functions)

## Done

### Basic set up 

- initial Gradle setup with multiple modules
- initial AWS account setup, complete with SSO access
- initial Kotlin CDK setup
- initial Docker setup for a Kotlin Lambda

### Infrastructure

- set up logging and metric publishing
- set up a Valkey cache with CDK
- ~~set up a Memcached cache with CDK~~ (I decided to only read about Memcache and take notes, but write code using it)

### Core logic

- implement token bucket algorithm against a Valkey cache
- implement leaky bucket algorithm against a Valkey cache
- implement sliding window counter algorithm against a Valkey cache
- ~~load the scripts rather than sending them every time~~ (actually Glide already takes care of that, as per the [documentation](https://valkey.io/valkey-glide/python/cluster_commands/#glide.async_commands.cluster_commands.ClusterCommands.invoke_script))
- look into expiring old keys for all implementations
- ~~implement token bucket algorithm against a Memcached cache~~ (I decided to only read about Memcache and take notes, but write code using it)
- ~~implement leaky bucket algorithm against a Memcached cache~~ (I decided to only read about Memcache and take notes, but write code using it)
- ~~implement sliding window counter algorithm against a Memcached cache~~ (I decided to only read about Memcache and take notes, but write code using it)

### Testing

#### Unit tests

- set up unit tests using a local Redis instance (maybe [this](https://www.baeldung.com/spring-embedded-redis)?)
- add unit tests for token bucket algorithm Valkey implementation
- add unit tests for leaky bucket algorithm Valkey implementation
- add unit tests for sliding window counter algorithm Valkey implementation
- add multi-threaded, real-time tests for all Valkey implementations
- ~~set up unit tests using a local Memcached instance ([reference](https://www.memcachier.com/documentation/local-usage))~~ (I decided to only read about Memcache and take notes, but write code using it)
- ~~add unit tests for token bucket algorithm Memcached implementation~~ (I decided to only read about Memcache and take notes, but write code using it)
- ~~add unit tests for leaky bucket algorithm Memcached implementation~~ (I decided to only read about Memcache and take notes, but write code using it)
- ~~add unit tests for sliding window counter algorithm Memcached implementation~~ (I decided to only read about Memcache and take notes, but write code using it)

#### Manual against real resources

- implement Lambda endpoint that can switch between throttler implementations, apply throttling and publish metrics
- implement a script capable to send traffic to the throttler Lambda following a pre-defined traffic pattern
