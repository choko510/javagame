package game;

import java.io.IOException;
import java.net.*;

public class ProxyCheck {

    /**
     * プロキシの有効性をチェックするメソッド
     *
     * @param proxyHost     プロキシホスト名
     * @param proxyPort     プロキシポート番号
     * @param targetUrl     プロキシ経由で接続を試みるURL
     * @param timeoutMillis タイムアウト時間（ミリ秒）
     * @return プロキシが有効な場合はtrue、そうでない場合はfalse
     */
    @SuppressWarnings("deprecation")
	public static boolean checkProxy(String proxyHost, int proxyPort, String targetUrl, int timeoutMillis) {
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            URL url = new URL(targetUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod("GET"); // または "HEAD" (HEADの方が高速な場合がある)

            // 実際に接続を試みる (getResponseCode() を呼ぶと接続が行われる)
            connection.getResponseCode();

            // 接続が成功すればOK (200番台のステータスコード)
            return connection.getResponseCode() >= 200 && connection.getResponseCode() < 300;

        } catch (IOException e) {
            // 接続エラー、タイムアウト、プロキシ認証エラーなどは無効とみなす
            //  System.err.println("Proxy " + proxyHost + ":" + proxyPort + " is invalid: " + e.getMessage()); // デバッグ用
            return false;
        }
    }
    
}