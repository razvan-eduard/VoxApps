# The shared auth vocabulary is parsed by Gson from every schema that declares a service, so its
# fields have to survive R8 in whichever app consumes this module.
-keep class com.voxapps.services.AuthDeclaration { *; }
