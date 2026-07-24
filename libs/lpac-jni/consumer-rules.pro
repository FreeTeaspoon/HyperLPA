# JNI exports use name-based static symbols; preserve the owner and native method names.
-keep,allowoptimization class net.typeblog.lpac_jni.LpacJni { *; }

# Native code looks these callback types and members up by exact JVM name/signature.
-keep,allowoptimization interface net.typeblog.lpac_jni.ApduInterface {
    void connect();
    void disconnect();
    int logicalChannelOpen(byte[]);
    void logicalChannelClose(int);
    byte[] transmit(int, byte[]);
}
-keep,allowoptimization interface net.typeblog.lpac_jni.HttpInterface {
    net.typeblog.lpac_jni.HttpInterface$HttpResponse transmit(java.lang.String, byte[], java.lang.String[]);
}
-keep,allowoptimization class net.typeblog.lpac_jni.HttpInterface$HttpResponse
-keepclassmembers,allowoptimization class net.typeblog.lpac_jni.HttpInterface$HttpResponse {
    int rcode;
    byte[] data;
}
-keep,allowoptimization interface net.typeblog.lpac_jni.ProfileDownloadCallback {
    boolean onStatusUpdate(net.typeblog.lpac_jni.ProfileDownloadState);
}
-keep,allowoptimization class net.typeblog.lpac_jni.ProfileDownloadState
-keep,allowoptimization class net.typeblog.lpac_jni.ProfileDownloadState$Preparing { <init>(); }
-keep,allowoptimization class net.typeblog.lpac_jni.ProfileDownloadState$Connecting { <init>(); }
-keep,allowoptimization class net.typeblog.lpac_jni.ProfileDownloadState$Authenticating { <init>(); }
-keep,allowoptimization class net.typeblog.lpac_jni.ProfileDownloadState$Downloading { <init>(); }
-keep,allowoptimization class net.typeblog.lpac_jni.ProfileDownloadState$Finalizing { <init>(); }
-keep,allowoptimization class net.typeblog.lpac_jni.ProfileDownloadState$ConfirmingDownload {
    <init>(net.typeblog.lpac_jni.RemoteProfileInfo);
}
-keep,allowoptimization class net.typeblog.lpac_jni.ProfileDownloadState$Installing { <init>(long, long); }
-keep,allowoptimization class net.typeblog.lpac_jni.RemoteProfileInfo {
    <init>(java.lang.String, java.lang.String, java.lang.String, net.typeblog.lpac_jni.ProfileClass, java.lang.String, java.lang.String, java.lang.String, java.lang.String);
}
-keep,allowoptimization enum net.typeblog.lpac_jni.ProfileClass {
    public static ** Testing;
    public static ** Provisioning;
    public static ** Operational;
}
