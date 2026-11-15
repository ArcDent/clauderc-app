# R8 rules for the release build. The libraries ship their own rules (kotlinx.serialization,
# OkHttp, coroutines, DataStore, Navigation), so only what they cannot know goes here.

# Navigation routes and protocol bodies are @Serializable; keep their generated serializers
# reachable by name as well, so a route or body looked up through serializer<T>() still works.
-keepclassmembers @kotlinx.serialization.Serializable class clauderc.app.** {
    static ** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class clauderc.app.**$$serializer { *; }
