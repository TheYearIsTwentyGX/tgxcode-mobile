# Referenced by the release build type in build.gradle.kts, which fails outright if
# this file is absent — so it exists even though it is nearly empty.
#
# Nearly, because the libraries this app minifies against ship their own consumer
# rules inside their artifacts: kotlinx.serialization keeps the generated
# `$$serializer` fields and `Companion.serializer()`, OkHttp keeps what its platform
# reflection needs, and Compose needs nothing kept. Restating those here would mean
# a second, staler copy of rules the libraries already maintain.
#
# The one thing worth saying: `AppJson` resolves every serializer through reified
# generics rather than by class lookup, so there is no reflective entry point for R8
# to miss. Adding a route that names a serializer at runtime is what would make this
# file need real content.

# Line numbers in a crash report from a minified build are worth the few KB.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
