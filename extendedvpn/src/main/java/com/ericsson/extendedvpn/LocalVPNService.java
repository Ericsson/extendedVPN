/*
 ** Copyright 2015, Mohamed Naufal
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.ericsson.extendedvpn;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalVPNService extends VpnService {
    private static final String TAG = LocalVPNService.class.getSimpleName();
    private static final String VPN_ADDRESS = "10.0.0.2"; // Only IPv4 support for now
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything

    public static final String BROADCAST_VPN_STATE = "xyz.hexene.localvpn.VPN_STATE";

    private static boolean isRunning = false;

    private ParcelFileDescriptor vpnInterface = null;

    private PendingIntent pendingIntent;

    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;
    private ExecutorService executorService;

    private Selector udpSelector;
    private Selector tcpSelector;

    @Override
    public IBinder onBind(Intent intent) {
        System.out.println("***Service binded.");
        VPNServiceBinder binder = new VPNServiceBinder();
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        setupVPN();
        try {
            udpSelector = Selector.open();
            tcpSelector = Selector.open();
            deviceToNetworkUDPQueue = new ConcurrentLinkedQueue<>();
            deviceToNetworkTCPQueue = new ConcurrentLinkedQueue<>();
            networkToDeviceQueue = new ConcurrentLinkedQueue<>();

            executorService = Executors.newFixedThreadPool(5);
            executorService.submit(new UDPInput(networkToDeviceQueue, udpSelector));
            executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, udpSelector, this));
            executorService.submit(new TCPInput(networkToDeviceQueue, tcpSelector));
            executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue, tcpSelector, this));
            executorService.submit(new VPNRunnable(vpnInterface.getFileDescriptor(),
                    deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue));
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
            Log.i(TAG, "Started");
        } catch (IOException e) {
            // TODO: Here and elsewhere, we should explicitly notify the user of any errors
            // and suggest that they stop the service, since we can't do it ourselves
            Log.e(TAG, "Error starting service", e);
            cleanup();
        }
    }

    private void setupVPN() {
        if (vpnInterface == null) {
            Builder builder = new Builder();
            builder.addAddress(VPN_ADDRESS, 32);
            builder.addRoute(VPN_ROUTE, 0);
            builder.addDnsServer("8.8.8.8");
            if (VPNConfig.getDnsServerAddress() != null && !VPNConfig.getDnsServerAddress().equals("")) {
                builder.addDnsServer(VPNConfig.getDnsServerAddress());
            }

            if (Build.VERSION.SDK_INT >= 21 && VPNConfig.getFilteredPackageNames() != null) {
                Iterator var2 = VPNConfig.getFilteredPackageNames().iterator();

                while (var2.hasNext()) {
                    String name = (String) var2.next();

                    try {
                        builder.addAllowedApplication(name);
                    } catch (PackageManager.NameNotFoundException var5) {
                        var5.printStackTrace();
                    }
                }
            }

            this.vpnInterface = builder.setSession(VPNConfig.getVpnName() != null
                    ? VPNConfig.getVpnName()
                    : this.getString(R.string.app_name)
            )
                    .setConfigureIntent(this.pendingIntent)
                    .establish();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = intent.getStringExtra("cmd");
        String testApp = intent.getStringExtra("testApp");
        if (cmd != null && cmd.contains("stop")) {
            onDestroy();
            stopService(new Intent(this, LocalVPNService.class));
        }
        return START_STICKY;
    }

    public static boolean isRunning() {
        return isRunning;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        executorService.shutdownNow();
        cleanup();
        Log.i(TAG, "Stopped");
    }

    private void cleanup() {
        deviceToNetworkTCPQueue = null;
        deviceToNetworkUDPQueue = null;
        networkToDeviceQueue = null;
        ByteBufferPool.clear();
        closeResources(udpSelector, tcpSelector, vpnInterface);
    }

    // TODO: Move this to a "utils" class for reuse
    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private static class VPNRunnable implements Runnable {
        private static final String TAG = VPNRunnable.class.getSimpleName();

        private FileDescriptor vpnFileDescriptor;

        private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
        private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
        private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

        public VPNRunnable(FileDescriptor vpnFileDescriptor,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
                           ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
            this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
            this.networkToDeviceQueue = networkToDeviceQueue;
        }

        @Override
        public void run() {
            Log.i(TAG, "Started");

            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
            FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

            try {
                ByteBuffer bufferToNetwork = null;
                boolean dataSent = true;
                boolean dataReceived;
                while (!Thread.interrupted()) {
                    if (dataSent)
                        bufferToNetwork = ByteBufferPool.acquire();
                    else
                        bufferToNetwork.clear();

                    // TODO: Block when not connected
                    int readBytes = vpnInput.read(bufferToNetwork);
                    if (readBytes > 0) {
                        dataSent = true;
                        bufferToNetwork.limit(readBytes);
                        bufferToNetwork.flip();

                        List<VPNPacketListener> packetListeners = VPNServiceBinder.getPacketListeners();
                        if (packetListeners != null) {
                            for (VPNPacketListener listener : packetListeners) {
                                listener.onVPNPacketReceived(Arrays.copyOf(bufferToNetwork.array(), bufferToNetwork.limit() + 4));
                            }
                        }

                        Packet packet = new Packet(bufferToNetwork);
                        if (packet.isUDP()) {
                            deviceToNetworkUDPQueue.offer(packet);
                        } else if (packet.isTCP()) {
                            deviceToNetworkTCPQueue.offer(packet);
                        } else {
                            Log.w(TAG, "Unknown packet type");
                            Log.w(TAG, packet.ip4Header.toString());
                            dataSent = false;
                        }
                    } else {
                        dataSent = false;
                    }

                    ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                    if (bufferFromNetwork != null) {
                        bufferFromNetwork.flip();
                        while (bufferFromNetwork.hasRemaining()) {
                            List<VPNPacketListener> packetListeners = VPNServiceBinder.getPacketListeners();
                            if (packetListeners != null) {
                                for (VPNPacketListener listener : packetListeners) {
                                    listener.onVPNPacketReceived(Arrays.copyOf(bufferFromNetwork.array(), bufferFromNetwork.limit() + 4));
                                }
                            }
                            vpnOutput.write(bufferFromNetwork);
                        }
                        dataReceived = true;

                        ByteBufferPool.release(bufferFromNetwork);
                    } else {
                        dataReceived = false;
                    }

                    // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                    // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                    if (!dataSent && !dataReceived)
                        Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Log.i(TAG, "Stopping");
            } catch (IOException e) {
                Log.w(TAG, e.toString(), e);
            } finally {
                closeResources(vpnInput, vpnOutput);
            }
        }
    }
}
