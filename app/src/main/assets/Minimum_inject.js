console.log("!!! FRIDA GADGET BOOTSTRAP SUCCESSFUL !!!");
console.log("Process ID: " + Process.id);

// Delay Java initialization
setTimeout(function() {
    console.log("[*] Attempting Java perform...");
    Java.perform(function() {
        console.log("[*] Java is ready!");
        var ActivityThread = Java.use("android.app.ActivityThread");
        var app = ActivityThread.currentApplication();
        if (app) {
            console.log("[*] App Package: " + app.getPackageName());
        }
    });
}, 2000);

rpc.exports = {
    ping: function() { return "pong"; }
};
