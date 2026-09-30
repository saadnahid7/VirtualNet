# Entry class is named by META-INF/xposed/java_init.list; R8 cannot see that reference.
-keep class com.droidrooter.virtualnet.hooks.VirtualNetModule { public <init>(); }
