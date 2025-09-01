package com.dici.distributedThrottler.lambda.logging

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

inline val <reified T> T.log: Logger
    get() = LogManager.getLogger(T::class.java)