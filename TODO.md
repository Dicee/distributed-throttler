## TODO

### Infrastructure

- set up logging and metric publishing
- set up a Valkey cache with CDK
- set up a Memcached cache with CDK

### Core logic

- implement token bucket algorithm against a Memcached cache
- implement leaky bucket algorithm against a Memcached cache
- implement sliding window counter algorithm against a Valkey cache
- implement sliding window counter algorithm against a Memcached cache

### Testing

#### Unit tests

- set up unit tests using a local Redis instance (maybe [this](https://www.baeldung.com/spring-embedded-redis)?)
- set up unit tests using a local Memcached instance ([reference](https://www.memcachier.com/documentation/local-usage))
- add unit tests for token bucket algorithm implementations 
- add unit tests for leaky bucket algorithm implementations 
- add unit tests for sliding window counter algorithm implementations

#### Manual against real resources

- implement Lambda endpoint that can switch between throttler implementations, apply throttling and publish metrics

### Minor improvements

- consider using coroutines, in particular for the leaky bucket implementations (though to do it well it's likely going to force moving everything to suspendable functions)

## Done

### Basic set up 

- initial Gradle setup with multiple modules
- initial AWS account setup, complete with SSO access
- initial Kotlin CDK setup
- initial Docker setup for a Kotlin Lambda
- implement token bucket algorithm against a Valkey cache
- implement leaky bucket algorithm against a Valkey cache