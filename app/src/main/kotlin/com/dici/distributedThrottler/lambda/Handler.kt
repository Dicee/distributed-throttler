package com.dici.distributedThrottler.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler

class Handler : RequestHandler<User, Unit> {
    override fun handleRequest(user: User, context: Context) {
        println("Hello ${user.name}")
    }
}

// mutable because Jackson uses the default constructor and a setter by default
data class User(var name: String) {
    @Suppress("unused") // required for deserialization
    constructor() : this("")
}