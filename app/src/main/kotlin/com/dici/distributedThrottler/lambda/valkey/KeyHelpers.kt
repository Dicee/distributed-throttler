package com.dici.distributedThrottler.lambda.valkey

fun hashSlotKey(hashSlot: String, subKey: String) = "{$hashSlot}:$subKey"
