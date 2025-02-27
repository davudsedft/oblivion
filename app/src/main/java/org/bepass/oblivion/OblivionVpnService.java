package org.bepass.oblivion;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import tun2socks.StartOptions;
import tun2socks.Tun2socks;

public class OblivionVpnService extends VpnService {
    public static final String FLAG_VPN_START = "org.bepass.oblivion.START";
    public static final String FLAG_VPN_STOP = "org.bepass.oblivion.STOP";
    static final int MSG_PERFORM_CONNECTION_TEST = 1;
    static final int MSG_CONNECTION_STATE_SUBSCRIBE = 2;
    static final int MSG_CONNECTION_STATE_UNSUBSCRIBE = 3;
    static final int MSG_TILE_STATE_SUBSCRIPTION_RESULT = 4;
    private static final String TAG = "oblivionVPN";
    private static final String PRIVATE_VLAN4_CLIENT = "172.19.0.1";
    private static final String PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1";
    private final Handler handler = new Handler();
    private final Messenger serviceMessenger = new Messenger(new IncomingHandler(this));
    private final Map<String, Messenger> connectionStateObservers = new HashMap<>();
    private final Runnable logRunnable = new Runnable() {
        @Override
        public void run() {
            String logMessages = Tun2socks.getLogMessages();
            if (!logMessages.isEmpty()) {
                try (FileOutputStream fos = getApplicationContext().openFileOutput("logs.txt", Context.MODE_APPEND)) {
                    fos.write((logMessages).getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            handler.postDelayed(this, 2000); // Poll every 2 seconds
        }
    };
    private Notification notification;
    private ParcelFileDescriptor mInterface;
    private Thread vpnThread;
    private String bindAddress;
    private FileManager fileManager;
    private ConnectionState lastKnownState = ConnectionState.DISCONNECTED;

    public static void startVpnService(Context context) {
        Intent intent = new Intent(context, OblivionVpnService.class);
        intent.setAction(OblivionVpnService.FLAG_VPN_START);
        ContextCompat.startForegroundService(context, intent);
    }


    public static void stopVpnService(Context context) {
        Intent intent = new Intent(context, OblivionVpnService.class);
        intent.setAction(OblivionVpnService.FLAG_VPN_STOP);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void registerConnectionStateObserver(String key, Messenger serviceMessenger, ConnectionStateChangeListener observer) {
        // Create a message for the service
        Message subscriptionMessage = Message.obtain(null, OblivionVpnService.MSG_CONNECTION_STATE_SUBSCRIBE);
        Bundle data = new Bundle();
        data.putString("key", key);
        subscriptionMessage.setData(data);
        // Create a Messenger for the reply from the service
        subscriptionMessage.replyTo = new Messenger(new Handler(incomingMessage -> {
            ConnectionState state = ConnectionState.valueOf(incomingMessage.getData().getString("state"));
            if (incomingMessage.what == OblivionVpnService.MSG_TILE_STATE_SUBSCRIPTION_RESULT) {
                observer.onChange(state);
            }
            return true;
        }));
        try {
            // Send the message
            serviceMessenger.send(subscriptionMessage);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public static void unregisterConnectionStateObserver(String key, Messenger serviceMessenger) {
        Message unsubscriptionMessage = Message.obtain(null, OblivionVpnService.MSG_CONNECTION_STATE_UNSUBSCRIBE);
        Bundle data = new Bundle();
        data.putString("key", key);
        unsubscriptionMessage.setData(data);
        try {
            serviceMessenger.send(unsubscriptionMessage);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public static Map<String, Integer> splitHostAndPort(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) {
            return null;
        }
        Map<String, Integer> result = new HashMap<>();
        String host;
        int port = -1; // Default port value if not specified

        // Check if the host part is an IPv6 address (enclosed in square brackets)
        if (hostPort.startsWith("[")) {
            int closingBracketIndex = hostPort.indexOf(']');
            if (closingBracketIndex > 0) {
                host = hostPort.substring(1, closingBracketIndex);
                if (hostPort.length() > closingBracketIndex + 1 && hostPort.charAt(closingBracketIndex + 1) == ':') {
                    // There's a port number after the closing bracket
                    port = Integer.parseInt(hostPort.substring(closingBracketIndex + 2));
                }
            } else {
                throw new IllegalArgumentException("Invalid IPv6 address format");
            }
        } else {
            // Handle IPv4 or hostname (split at the last colon)
            int lastColonIndex = hostPort.lastIndexOf(':');
            if (lastColonIndex > 0) {
                host = hostPort.substring(0, lastColonIndex);
                port = Integer.parseInt(hostPort.substring(lastColonIndex + 1));
            } else {
                host = hostPort; // No port specified
            }
        }

        result.put(host, port);
        return result;
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            int port = socket.getLocalPort();
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore IOException on close()
            }
            return port;
        } catch (IOException ignored) {
        }
        throw new IllegalStateException("Could not find a free TCP/IP port to start embedded Jetty HTTP Server on");
    }

    public static String isLocalPortInUse(String bindAddress) {
        Map<String, Integer> result = splitHostAndPort(bindAddress);
        if (result == null) {
            return "exception";
        }
        int socksPort = result.values().iterator().next();
        try {
            // ServerSocket try to open a LOCAL port
            new ServerSocket(socksPort).close();
            // local port can be opened, it's available
            return "false";
        } catch (IOException e) {
            // local port cannot be opened, it's in use
            return "true";
        }
    }

    private static void performConnectionTest(String bindAddress, ConnectionStateChangeListener changeListener) {
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            boolean isSuccessful = false;

            while (System.currentTimeMillis() - startTime < 2 * 60 * 1000) { // 2 minutes
                String result = isLocalPortInUse(bindAddress);
                if (result.contains("exception")) {
                    if (changeListener != null)
                        changeListener.onChange(ConnectionState.DISCONNECTED);
                    return;
                }
                if (result.contains("true")) {
                    isSuccessful = true;
                    break;
                }
                try {
                    Thread.sleep(1000); // Sleep for a second before retrying
                } catch (InterruptedException e) {
                    break; // Exit if interrupted (e.g., service stopping)
                }
            }
            if (changeListener != null)
                changeListener.onChange(isSuccessful ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED);
        }).start();
    }

    private String getBindAddress() {
        String port = fileManager.getString("USERSETTING_port");
        boolean enableLan = fileManager.getBoolean("USERSETTING_lan");
        if (OblivionVpnService.isLocalPortInUse("127.0.0.1:" + port).equals("true")) {
            port = String.valueOf(findFreePort());
        }
        String Bind = "";
        Bind += "127.0.0.1:" + port;
        if (enableLan) {
            Bind = "0.0.0.0:" + port;
        }
        return Bind;
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        /*
        If we override onBind, we never receive onRevoke.
        return superclass onBind when action is SERVICE_INTERFACE to receive onRevoke lifecycle call.
         */
        if (action != null && action.equals(VpnService.SERVICE_INTERFACE)) {
            return super.onBind(intent);
        }
        return serviceMessenger.getBinder();
    }

    private void clearLogFile() {
        try (FileOutputStream fos = getApplicationContext().openFileOutput("logs.txt", Context.MODE_PRIVATE)) {
            fos.write("".getBytes()); // Overwrite with empty content
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && FLAG_VPN_START.equals(intent.getAction())) {
            fileManager = FileManager.getInstance(this);
            bindAddress = getBindAddress();
            runVpn();
            performConnectionTest(bindAddress, this::setLastKnownState);
            return START_STICKY;
        } else if (intent != null && FLAG_VPN_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler.post(logRunnable);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(logRunnable);
    }

    @Override
    public void onRevoke() {
        stopVpn();
    }

    private void runVpn() {
        setLastKnownState(ConnectionState.CONNECTING);
        Log.i(TAG, "Clearing Logs");
        clearLogFile();
        Log.i(TAG, "Create Notification");
        createNotification();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(1, notification);
        } else {
            startForeground(1, notification,
                    FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        }
        Log.i(TAG, "Configuring VPN service");
        configure();
    }

    private void stopVpn() {
        setLastKnownState(ConnectionState.DISCONNECTED);
        Log.i(TAG, "Stopping VPN");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.deleteNotificationChannel("oblivion");
            }
        }
        try {
            stopForeground(true);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            Tun2socks.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (mInterface != null) {
            try {
                mInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing the VPN interface", e);
            }
        }

        if (vpnThread != null) {
            try {
                vpnThread.join();
                vpnThread.stop();
            } catch (Exception e) {
            }
        }
    }

    private void publishConnectionState(ConnectionState state) {
        if (!connectionStateObservers.isEmpty()) {
            for (String observerKey : connectionStateObservers.keySet())
                publishConnectionStateTo(observerKey, state);
        }
    }

    private void publishConnectionStateTo(String observerKey, ConnectionState state) {
        Log.i("Publisher", "Publishing state " + state + " to " + observerKey);
        Messenger observer = connectionStateObservers.get(observerKey);
        if (observer == null) return;
        Bundle args = new Bundle();
        args.putString("state", state.toString());
        Message replyMsg = Message.obtain(null, MSG_TILE_STATE_SUBSCRIPTION_RESULT);
        replyMsg.setData(args);
        try {
            observer.send(replyMsg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void setLastKnownState(ConnectionState lastKnownState) {
        this.lastKnownState = lastKnownState;
        publishConnectionState(lastKnownState);
    }

    private String getNotificationText() {
        boolean usePsiphon = fileManager.getBoolean("USERSETTING_psiphon");
        boolean useWarp = fileManager.getBoolean("USERSETTING_gool");

        if (usePsiphon) {
            String countryCode = fileManager.getString("USERSETTING_country");
            String countryName = "".equals(countryCode) ? "Automatic" : CountryUtils.getCountryName(countryCode);
            return "Psiphon (" + countryName + ") in Warp";

        } else if (useWarp) {
            return "Warp in Warp";

        } else {
            return "Warp";
        }
    }

    private void createNotification() {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        NotificationChannelCompat notificationChannel = new NotificationChannelCompat.Builder(
                "vpn_service", NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Vpn Service")
                .build();
        notificationManager.createNotificationChannel(notificationChannel);
        Intent disconnectIntent = new Intent(this, OblivionVpnService.class);
        disconnectIntent.setAction(OblivionVpnService.FLAG_VPN_STOP);
        PendingIntent disconnectPendingIntent = PendingIntent.getService(
                this, 0, disconnectIntent, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        notification = new NotificationCompat.Builder(this, notificationChannel.getId())
                .setContentTitle("Vpn Service")
                .setContentText("Oblivion - " + getNotificationText())
                .setSmallIcon(R.mipmap.ic_notification)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setAutoCancel(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setDefaults(NotificationCompat.FLAG_ONLY_ALERT_ONCE)
                .setContentIntent(contentPendingIntent)
                .addAction(0, "Disconnect", disconnectPendingIntent)
                .build();
    }

    public void addConnectionStateObserver(String key, Messenger messenger) {
        connectionStateObservers.put(key, messenger);
    }

    public void removeConnectionStateObserver(String key) {
        connectionStateObservers.remove(key);
    }

    private StartOptions calculateArgs() {
        StartOptions so = new StartOptions();
        so.setPath(getApplicationContext().getFilesDir().getAbsolutePath());
        so.setVerbose(true);
        so.setVerbose(true);
        String endpoint = fileManager.getString("USERSETTING_endpoint", "notset");
        String country = fileManager.getString("USERSETTING_country", "");
        String license = fileManager.getString("USERSETTING_license", "notset");

        boolean enablePsiphon = fileManager.getBoolean("USERSETTING_psiphon", false);
        boolean enableGool = fileManager.getBoolean("USERSETTING_gool", false);

        if (!endpoint.contains("engage.cloudflareclient.com")) {
            so.setEndpoint(endpoint);
        } else {
            so.setEndpoint("notset");
            so.setScan(true);
        }

        so.setBindAddress(bindAddress);

        if (!license.trim().isEmpty()) {
            so.setLicense(license.trim());
        } else {
            so.setLicense("notset");
        }

        if (enablePsiphon && !enableGool) {
            so.setPsiphonEnabled(true);
            if (!country.trim().isEmpty() && country.length() == 2) {
                so.setCountry(country.trim());
            }
        }

        if (!enablePsiphon && enableGool) {
            so.setGool(true);
        }

        so.setRtt(800);

        return so;
    }

    private void configure() {
        VpnService.Builder builder = new VpnService.Builder();
        try {
            builder.setSession("oblivion")
                    .setMtu(1500)
                    .addAddress(PRIVATE_VLAN4_CLIENT, 30)
                    .addAddress(PRIVATE_VLAN6_CLIENT, 126)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("1.0.0.1")
                    .addDnsServer("2001:4860:4860::8888")
                    .addDnsServer("2001:4860:4860::8844")
                    .addDisallowedApplication(getPackageName())
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0);
            fileManager.getStringSet("splitTunnelApps", new HashSet<>());
            SplitTunnelMode splitTunnelMode = SplitTunnelMode.getSplitTunnelMode(fileManager);
            if (splitTunnelMode == SplitTunnelMode.BLACKLIST) {
                for (String packageName : getSplitTunnelApps(fileManager)) {
                    builder.addDisallowedApplication(packageName);
                }
            }

        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        mInterface = builder.establish();
        Log.i(TAG, "Interface created");

        StartOptions so = calculateArgs();
        so.setTunFd(mInterface.getFd());

        vpnThread = new Thread(() -> Tun2socks.runWarp(so));
        vpnThread.start();
    }

    private static Set<String> getSplitTunnelApps(FileManager fm) {
        return fm.getStringSet("splitTunnelApps", new HashSet<>());
    }

    private static class IncomingHandler extends Handler {
        private final WeakReference<OblivionVpnService> serviceRef;

        IncomingHandler(OblivionVpnService service) {
            serviceRef = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            final Message message = new Message();
            message.copyFrom(msg);
            OblivionVpnService service = serviceRef.get();
            if (service == null) return;
            switch (msg.what) {
                case MSG_PERFORM_CONNECTION_TEST: {
                    performConnectionTest(service.bindAddress, new ConnectionStateChangeListener() {
                        @Override
                        public void onChange(ConnectionState state) {
                            service.setLastKnownState(state);
                            Bundle data = new Bundle();
                            if (state == ConnectionState.DISCONNECTED) {
                                data.putBoolean("success", false);
                                Message replyMsg = Message.obtain(null, MSG_PERFORM_CONNECTION_TEST);
                                replyMsg.setData(data);
                                try {
                                    message.replyTo.send(replyMsg);
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                }
                            } else {
                                data.putBoolean("success", true);
                                Message replyMsg = Message.obtain(null, MSG_PERFORM_CONNECTION_TEST);
                                replyMsg.setData(data);
                                try {
                                    message.replyTo.send(replyMsg);
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                }
                            }

                        }
                    });
                    break;
                }
                case MSG_CONNECTION_STATE_SUBSCRIBE: {
                    String key = message.getData().getString("key");
                    if (key == null)
                        throw new RuntimeException("No key was provided for the connection state observer");
                    if (service.connectionStateObservers.containsKey(key)) {
                        //Already subscribed
                        return;
                    }
                    service.addConnectionStateObserver(key, message.replyTo);
                    service.publishConnectionStateTo(key, service.lastKnownState);
                    break;
                }
                case MSG_CONNECTION_STATE_UNSUBSCRIBE: {
                    String key = message.getData().getString("key");
                    if (key == null)
                        throw new RuntimeException("No observer was specified to unregister");
                    service.removeConnectionStateObserver(key);
                    break;
                }
                default: {
                    super.handleMessage(msg);
                }
            }
        }
    }
}
