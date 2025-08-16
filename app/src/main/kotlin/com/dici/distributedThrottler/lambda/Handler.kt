package com.dici.distributedThrottler.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler

object Handler : RequestHandler<User, Unit> {
    override fun handleRequest(user: User, context: Context) {
        println("Hello ${user.name}")
    }
}

data class User(val name: String)