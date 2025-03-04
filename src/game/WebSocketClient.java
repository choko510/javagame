package game;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class WebSocketClient {

    private String uri;
    private String proxyHost;
    private int proxyPort;
    private Socket sock;
    private volatile boolean connected = false; // 接続状態 (volatile: 複数スレッドからアクセスされるため)
    private String scheme;
    private String host;
    private int port;
    private String path;
    private Consumer<String> messageHandler; // メッセージ受信時のハンドラ
    private volatile boolean manuallyClosed = false; // 手動で close() が呼ばれたかどうかのフラグ
    private static final int RECONNECT_DELAY_MS = 3000; // 再接続試行間隔 (ミリ秒)
    private Thread reconnectThread; // 再接続用スレッド
    private ScheduledExecutorService pingExecutor; // Ping 送信用スケジューラ
    private static final int PING_INTERVAL_SECONDS = 30;  //Ping送信間隔

    /**
     * WebSocketClient コンストラクタ.
     *
     * @param uri            接続先 URI (例: "ws://example.com:8080/path")
     * @param proxyHost      プロキシホスト (null または空文字列の場合はプロキシを使用しない)
     * @param proxyPort      プロキシポート
     * @param messageHandler メッセージ受信時の処理 (Consumer<String>)
     */
    public WebSocketClient(String uri, String proxyHost, int proxyPort, Consumer<String> messageHandler) {
        this.uri = uri;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.messageHandler = messageHandler;
        parseUri();
    }

    /**
     * WebSocket サーバーに接続する (同期処理).
     *
     * @throws IOException 接続エラーが発生した場合
     */
    public synchronized void connect() throws IOException {
        manuallyClosed = false; // 手動クローズフラグをリセット
        if (reconnectThread != null && reconnectThread.isAlive()) {
            reconnectThread.interrupt(); // 既存の再接続スレッドがあれば停止
        }
        connectInternal();
    }

    /**
     * 内部接続処理 (プロキシの有無に応じて分岐).
     *
     * @throws IOException 接続エラーが発生した場合
     */
    private void connectInternal() throws IOException {
        try {
            if (proxyHost != null && !proxyHost.isEmpty()) {
                try {
                    connectViaProxy(); // プロキシ経由で接続
                } catch (IOException e) {
                    System.err.println("Proxy connection failed, attempting direct connection: " + e.getMessage());
                    directConnect(); // プロキシ接続に失敗したら直接接続を試みる
                }
            } else {
                directConnect(); // 直接接続
            }

            if (connected) {
                startReceiveThread(); // メッセージ受信スレッドを開始
                startPing(); // Ping 送信開始
                System.out.println("WebSocket connected to " + uri);
            }
        } catch (IOException e) {
            System.err.println("Connection failed: " + e.getMessage());
            handleDisconnection(e); // 切断処理
        }
    }

    /**
     * プロキシ経由で WebSocket サーバーに接続する.
     *
     * @throws IOException 接続エラーが発生した場合
     */
    private void connectViaProxy() throws IOException {
        sock = new Socket();
        sock.connect(new InetSocketAddress(proxyHost, proxyPort), RECONNECT_DELAY_MS * 2); // 接続タイムアウトを設定

        OutputStream out = sock.getOutputStream();
        InputStream in = sock.getInputStream();

        // プロキシへの CONNECT リクエストを送信
        String connectCmd = String.format(
                "CONNECT %s:%d HTTP/1.1\r\n" +
                        "Host: %s:%d\r\n" +
                        "\r\n",
                host, port, host, port
        );
        out.write(connectCmd.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // プロキシからの応答を読み取る
        ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while (responseBytes.toString(StandardCharsets.UTF_8).indexOf("\r\n\r\n") == -1 && (bytesRead = in.read(buffer)) != -1) {
            responseBytes.write(buffer, 0, bytesRead);
        }
        String proxyResponse = responseBytes.toString(StandardCharsets.UTF_8);

        // プロキシ接続の成功を確認 (HTTP ステータスコード 200)
        if (!(proxyResponse.contains(" 200 ") || proxyResponse.contains(" 200\r\n"))) {
            sock.close();
            throw new IOException("Proxy connection failed with HTTP status code: " + proxyResponse.split(" ")[1] + "\nResponse:\n" + proxyResponse);
        }

        // wss (WebSocket over SSL/TLS) の場合は、ソケットを SSL ソケットでラップする
        if (scheme.equals("wss")) {
            wrapSocketSsl();
        }

        performHandshake(); // WebSocket ハンドシェイクを実行
    }

    /**
     * WebSocket サーバーに直接接続する.
     *
     * @throws IOException 接続エラーが発生した場合
     */
    private void directConnect() throws IOException {
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), RECONNECT_DELAY_MS * 2); // 接続タイムアウトを設定

        // wss の場合は、ソケットを SSL ソケットでラップする
        if (scheme.equals("wss")) {
            wrapSocketSsl();
        }

        performHandshake(); // WebSocket ハンドシェイクを実行
    }

    /**
     * URI を解析して、スキーム、ホスト、ポート、パスを取得する.
     */
    private void parseUri() {
        try {
            URI parsedUri = new URI(uri);
            scheme = parsedUri.getScheme();
            host = parsedUri.getHost();
            path = parsedUri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            port = parsedUri.getPort();

            // スキームが ws または wss であることを確認
            if (!scheme.equals("ws") && !scheme.equals("wss")) {
                throw new IllegalArgumentException("Unsupported scheme. Use ws:// or wss://");
            }

            // ポートが指定されていない場合は、デフォルトのポートを設定
            if (port == -1) {
                port = scheme.equals("wss") ? 443 : 80;
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI: " + uri, e);
        }
    }

    /**
     * ソケットを SSL ソケットでラップする (TLS ハンドシェイクも実行).
     *
     * @throws IOException ラップ中にエラーが発生した場合
     */
    private void wrapSocketSsl() throws IOException {
        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        sock = sslSocketFactory.createSocket(sock, host, port, true); // auto-close = true
        ((SSLSocket) sock).startHandshake(); // TLS ハンドシェイクを開始
    }

    /**
     * WebSocket ハンドシェイクを実行する.
     *
     * @throws IOException ハンドシェイク中にエラーが発生した場合
     */
    private void performHandshake() throws IOException {
        SecureRandom secureRandom = new SecureRandom();
        byte[] nonce = new byte[16];
        secureRandom.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);

        // WebSocket ハンドシェイクリクエストを構築
        String handshake = String.format(
                "GET %s HTTP/1.1\r\n" +
                        "Host: %s:%d\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: %s\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n",
                path, host, port, key
        );

        OutputStream out = sock.getOutputStream();
        out.write(handshake.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // サーバーからの応答を読み取る
        ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while (responseBytes.toString(StandardCharsets.UTF_8).indexOf("\r\n\r\n") == -1 && (bytesRead = sock.getInputStream().read(buffer)) != -1) {
            responseBytes.write(buffer, 0, bytesRead);
        }

        String response = responseBytes.toString(StandardCharsets.UTF_8);

        // ハンドシェイクの成功を確認 (HTTP ステータスコード 101)
        if (response.indexOf(" 101 ") == -1) {
            sock.close();
            throw new IOException("Handshake failed:\n" + response);
        }

        // Sec-WebSocket-Accept ヘッダーを検証
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
            byte[] digestBytes = digest.digest();
            String expectedAccept = Base64.getEncoder().encodeToString(digestBytes);

            if (response.indexOf("Sec-WebSocket-Accept: " + expectedAccept) == -1) {
                sock.close();
                throw new IOException("Invalid Sec-WebSocket-Accept header\nResponse:\n" + response + "\nExpected Accept:\n" + expectedAccept);
            }
        } catch (NoSuchAlgorithmException e) {
            sock.close();
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }

        connected = true; // 接続確立
    }

    /**
     * WebSocket サーバーにメッセージを送信する.
     *
     * @param message 送信するメッセージ
     * @throws IOException 送信中にエラーが発生した場合
     */
    public void send(String message) throws IOException {
        if (!connected) {
            throw new IllegalStateException("Not connected");
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x81); // FIN ビットとテキストフレーム (opcode 0x01) を設定

        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        int msgLen = messageBytes.length;

        // ペイロード長を設定
        if (msgLen <= 125) {
            header.write(0x80 | msgLen); // マスクビットとペイロード長
        } else if (msgLen <= 65535) {
            header.write(0x80 | 126); // マスクビットと拡張ペイロード長 (16ビット)
            header.write(ByteBuffer.allocate(2).putShort((short) msgLen).array());
        } else {
            header.write(0x80 | 127); // マスクビットと拡張ペイロード長 (64ビット)
            header.write(ByteBuffer.allocate(8).putLong(msgLen).array());
        }

        // マスクキーを生成
        SecureRandom secureRandom = new SecureRandom();
        byte[] maskKey = new byte[4];
        secureRandom.nextBytes(maskKey);
        header.write(maskKey); // マスクキーをヘッダーに追加

        // メッセージをマスクする
        byte[] masked = new byte[msgLen];
        for (int i = 0; i < msgLen; i++) {
            masked[i] = (byte) (messageBytes[i] ^ maskKey[i % 4]);
        }

        // ヘッダーとマスクされたメッセージを送信
        OutputStream out = sock.getOutputStream();
        out.write(header.toByteArray());
        out.write(masked);
        out.flush();
    }

    /**
     * メッセージ受信スレッドを開始する.
     */
    private void startReceiveThread() {
        Thread receiveThread = new Thread(this::receive);
        receiveThread.setDaemon(true); // デーモンスレッドとして実行 (プログラム終了時に自動的に終了)
        receiveThread.start();
    }

    /**
     * WebSocket サーバーからメッセージを受信する (別スレッドで実行).
     */
    public void receive() {
        try {
            InputStream in = sock.getInputStream();
            while (connected) {
                // ヘッダーを読み取る
                byte[] headerBytes = recvN(2);
                if (headerBytes == null) {
                    break; // ソケットが閉じられたか、エラーが発生
                }

                boolean fin = (headerBytes[0] & 0x80) != 0; // FIN ビット
                int opcode = headerBytes[0] & 0x0F;        // オペコード
                boolean masked = (headerBytes[1] & 0x80) != 0; // マスクビット
                int payloadLen = headerBytes[1] & 0x7F;     // ペイロード長

                // ペイロード長を計算 (拡張ペイロード長の場合)
                if (payloadLen == 126) {
                    byte[] lenBytes = recvN(2);
                    if (lenBytes == null) {
                        break;
                    }
                    payloadLen = ByteBuffer.wrap(lenBytes).getShort() & 0xFFFF;
                } else if (payloadLen == 127) {
                    byte[] lenBytes = recvN(8);
                    if (lenBytes == null) {
                        break;
                    }
                    payloadLen = (int) ByteBuffer.wrap(lenBytes).getLong(); // int にキャスト (long は扱わない)
                }

                // マスクキーを取得
                byte[] maskKey = null;
                if (masked) {
                    maskKey = recvN(4);
                    if (maskKey == null) {
                        break;
                    }
                }

                // ペイロードを読み取る
                byte[] payload = recvN(payloadLen);
                if (payload == null) {
                    break;
                }

                // ペイロードをマスク解除
                if (masked && maskKey != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
                    }
                }

                // オペコードに応じて処理
                if (opcode == 0x01) { // テキストフレーム
                    String message = new String(payload, StandardCharsets.UTF_8);
                    if (messageHandler != null) {
                        messageHandler.accept(message); // メッセージハンドラを呼び出す
                    }
                } else if (opcode == 0x08) { // クローズフレーム
                    System.out.println("Connection closed by server.");
                    if (!manuallyClosed) {
                        try {
                            connectInternal(); // 再接続試行
                        } catch (IOException reconnectException) {
                            System.err.println("Reconnect failed: " + reconnectException.getMessage());
                        }
                    }else {
                    	closeInternal(); // 接続を閉じる
                    }
                    break;
                } else if (opcode == 0x09) { // Ping フレーム
                    sendPong(payload); // Pong フレームを返信
                }
            }
        } catch (IOException e) {
            handleDisconnection(e); // 切断処理
        }
    }

    /**
     * Pong フレームを送信する.
     *
     * @param pingPayload Ping フレームのペイロード
     * @throws IOException 送信中にエラーが発生した場合
     */
    private void sendPong(byte[] pingPayload) throws IOException {
        if (!connected) {
            return;
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x8A); // FIN ビットと Pong フレーム (opcode 0x0A)

        int payloadLen = pingPayload.length;
        // クライアント側では必ずマスクする必要がある
        if (payloadLen <= 125) {
            header.write(0x80 | payloadLen);
        } else if (payloadLen <= 65535) {
            header.write(0x80 | 126);
            header.write(ByteBuffer.allocate(2).putShort((short) payloadLen).array());
        } else {
            header.write(0x80 | 127);
            header.write(ByteBuffer.allocate(8).putLong(payloadLen).array());
        }

        // マスクキーを生成してヘッダーに追加
        SecureRandom secureRandom = new SecureRandom();
        byte[] maskKey = new byte[4];
        secureRandom.nextBytes(maskKey);
        header.write(maskKey);

        // ペイロードをマスクする
        byte[] maskedPayload = new byte[payloadLen];
        for (int i = 0; i < payloadLen; i++) {
            maskedPayload[i] = (byte) (pingPayload[i] ^ maskKey[i % 4]);
        }

        OutputStream out = sock.getOutputStream();
        out.write(header.toByteArray());
        out.write(maskedPayload);
        out.flush();
    }

    /**
     * Ping フレームを定期的に送信する.
     */
    private void startPing() {
        if (pingExecutor == null || pingExecutor.isShutdown()) {
            pingExecutor = Executors.newSingleThreadScheduledExecutor();
        }

        pingExecutor.scheduleAtFixedRate(() -> {
            try {
                if (connected) {
                    sendPing(generatePingPayload()); // Ping フレームを送信
                }
            } catch (IOException e) {
                System.err.println("Error sending ping: " + e.getMessage());
                // 必要に応じて再接続処理などをここに記述 (handleDisconnection() を呼び出すなど)
            }
        }, 0, PING_INTERVAL_SECONDS, TimeUnit.SECONDS); // 初回遅延 0 秒、指定時間間隔
    }

    /**
     * Ping フレームを送信する.
     * @param  pingPayload 送信するランダムなbyte配列
     * @throws IOException 送信中にエラーが発生した場合
     */
    private void sendPing(byte[] pingPayload) throws IOException {
        if (!connected) {
            return;
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x89); // FIN ビットと Ping フレーム (opcode 0x09) を設定

        int payloadLen = pingPayload.length;
        // ペイロード長を設定
        if (payloadLen <= 125) {
            header.write(0x80 | payloadLen); // マスクビットとペイロード長
        } else if (payloadLen <= 65535) {
            header.write(0x80 | 126); // マスクビットと拡張ペイロード長 (16ビット)
            header.write(ByteBuffer.allocate(2).putShort((short) payloadLen).array());
        } else {
            header.write(0x80 | 127); // マスクビットと拡張ペイロード長 (64ビット)
            header.write(ByteBuffer.allocate(8).putLong(payloadLen).array());
        }

        // マスクキーを生成
        SecureRandom secureRandom = new SecureRandom();
        byte[] maskKey = new byte[4];
        secureRandom.nextBytes(maskKey);
        header.write(maskKey); // マスクキーをヘッダーに追加

        // ペイロードをマスクする
        byte[] masked = new byte[payloadLen];
        for (int i = 0; i < payloadLen; i++) {
            masked[i] = (byte) (pingPayload[i] ^ maskKey[i % 4]);
        }

        // ヘッダーとマスクされたペイロードを送信
        OutputStream out = sock.getOutputStream();
        out.write(header.toByteArray());
        out.write(masked);
        out.flush();
    }

    /**
     * ランダムなPingのペイロードを生成する
     * @return ランダムなbyte配列
     */
    private byte[] generatePingPayload() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] pingPayload = new byte[10]; // 10バイトのランダムなペイロード
        secureRandom.nextBytes(pingPayload);
        return pingPayload;
    }

    /**
     * 指定されたバイト数だけ入力ストリームから読み込む.
     *
     * @param n 読み込むバイト数
     * @return 読み込んだバイト配列 (EOF に達した場合は null)
     * @throws IOException 読み込み中にエラーが発生した場合
     */
    private byte[] recvN(int n) throws IOException {
        byte[] data = new byte[n];
        int bytesRead = 0;
        while (bytesRead < n) {
            int read = sock.getInputStream().read(data, bytesRead, n - bytesRead);
            if (read == -1) {
                return null; // EOF に達した
            }
            bytesRead += read;
        }
        return data;
    }

    /**
     * WebSocket 接続が確立しているかどうかを確認する.
     *
     * @return 接続されている場合は true、そうでない場合は false
     */
    public synchronized boolean isConnected() {
        return connected;
    }

    /**
     * WebSocket 接続を閉じる (手動クローズ).
     */
    public synchronized void close() {
        manuallyClosed = true; // 手動クローズフラグを設定
        if (connected) {
            try {
                sendCloseFrame(); // Close フレームを送信
            } catch (IOException e) {
                System.err.println("Error sending close frame: " + e.getMessage());
            }
        }
        closeInternal(); // 内部クローズ処理
        if (reconnectThread != null && reconnectThread.isAlive()) {
            reconnectThread.interrupt(); // 再接続スレッドを停止
        }
        System.out.println("WebSocket closed manually.");
    }

    /**
     * Close フレームを送信する.
     *
     * @throws IOException 送信エラー
     */
    private void sendCloseFrame() throws IOException {
        // Close フレームの構築 (ステータスコード 1000: Normal Closure)
        ByteArrayOutputStream closeFrame = new ByteArrayOutputStream();
        closeFrame.write(0x88); // FIN + Close frame (opcode 0x08)
        closeFrame.write(0x82); // Masked, Payload length = 2 (status code)
        SecureRandom secureRandom = new SecureRandom();
        byte[] maskKey = new byte[4];
        secureRandom.nextBytes(maskKey);
        closeFrame.write(maskKey);

        short statusCode = 1000; // Normal Closure
        byte[] statusCodeBytes = ByteBuffer.allocate(2).putShort(statusCode).array();
        byte[] maskedStatusCode = new byte[2];
        for (int i = 0; i < 2; i++) {
            maskedStatusCode[i] = (byte) (statusCodeBytes[i] ^ maskKey[i % 4]);
        }
        closeFrame.write(maskedStatusCode);

        OutputStream out = sock.getOutputStream();
        out.write(closeFrame.toByteArray());
        out.flush();
    }

    /**
     * 内部クローズ処理 (ソケットを閉じ、接続状態を false にする).
     */
    private void closeInternal() {
        if (connected) {
            connected = false;
            try {
                if (sock != null && !sock.isClosed()) {
                    sock.close();
                }
            } catch (IOException e) {
                // クローズ時の例外は無視
            } finally {
                sock = null; // ソケット参照をクリア
                if (pingExecutor != null) {
                    pingExecutor.shutdownNow(); // ping スレッドを停止
                }
            }
        }
    }

    /**
     * 切断処理 (エラーハンドリングと再接続).
     *
     * @param e 発生した IOException
     */
    private synchronized void handleDisconnection(IOException e) {
        if (connected && !manuallyClosed) {
            System.err.println("Connection error: " + e.getMessage());
            closeInternal(); // 接続を閉じる
            if (!manuallyClosed) {
                startReconnectThread(); // 再接続スレッドを開始
            }
        } else if (connected) { // 手動クローズ
            closeInternal();
        }
    }

    /**
     * 再接続スレッドを開始する.
     */
    private void startReconnectThread() {
        if (reconnectThread == null || !reconnectThread.isAlive()) {
            reconnectThread = new Thread(() -> {
                while (!manuallyClosed && !connected) {
                    System.out.println("Attempting to reconnect in " + RECONNECT_DELAY_MS / 1000 + " seconds...");
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); // 割り込みフラグを再設定
                        return; // スリープ中に割り込まれた場合は再接続ループを終了
                    }
                    if (!manuallyClosed) {
                        try {
                            connectInternal(); // 再接続試行
                        } catch (IOException reconnectException) {
                            System.err.println("Reconnect failed: " + reconnectException.getMessage());
                        }
                    }
                }
            });
            reconnectThread.setDaemon(true); // デーモンスレッドとして実行
            reconnectThread.start();
        }
    }


    // 以下、getter/setter メソッド

    public String getProxyHost() {
        return proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyConfig(String proxyHost, int proxyPort) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
    }
    @Override
    public String toString() {
        return "WebSocketClient{" +
                "uri='" + uri + '\'' +
                ", proxyHost='" + proxyHost + '\'' +
                ", proxyPort=" + proxyPort +
                ", connected=" + connected +
                ", manuallyClosed=" + manuallyClosed +
                '}';
    }
}