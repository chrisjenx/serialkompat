package com.example

import kotlinx.serialization.json.Json

// The `jsonInstance` target the docs name as `com.example.WireJson.instance` (docs/configuration.md).
// --8<-- [start:wire-json]
object WireJson {
    val instance = Json { ignoreUnknownKeys = true }
}
// --8<-- [end:wire-json]
