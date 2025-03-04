package game;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer;
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer.Cell;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.Input.TextInputListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.function.Consumer;

public class Game implements ApplicationListener, InputProcessor {

    // ゲーム状態：ロビー（メニュー状態）とプレイ中
    private enum GameState {
        LOBBY, PLAYING, GAME_OVER_SCREEN
    }
    private GameState gameState = GameState.LOBBY;

    // --- プレイヤー関連 ---
    static class Player {
        static float WIDTH;
        static float HEIGHT;
        static float MAX_VELOCITY = 10f;
        static float JUMP_VELOCITY = 40f;
        static float DAMPING = 0.85f;
        static final int MAX_DEATHS = 3;

        final Vector2 position = new Vector2();
        final Vector2 velocity = new Vector2();
        boolean grounded = false;
        int playerNumber;
        float gunAngle = 0f;
        float damage = 0f;
        int deaths = 0; // デス数の追加
        boolean gameOver = false;

        public Player(int playerNumber) {
            this.playerNumber = playerNumber;
        }

        // 入力処理（自分のみ）
        public void handleInput(float deltaTime, int myPlayerId) {
            if (this.playerNumber == myPlayerId && myPlayerId != -1 && !gameOver) { // ゲームオーバー中は操作不可
                if (Gdx.input.isKeyPressed(Keys.SPACE) && grounded) {
                    velocity.y += JUMP_VELOCITY;
                    grounded = false;
                }
                if (Gdx.input.isKeyPressed(Keys.A) || Gdx.input.isKeyPressed(Keys.LEFT)) {
                    velocity.x = -MAX_VELOCITY;
                }
                if (Gdx.input.isKeyPressed(Keys.D) || Gdx.input.isKeyPressed(Keys.RIGHT)) {
                    velocity.x = MAX_VELOCITY;
                }
            }
        }

        public void update(float deltaTime) {
            if (!gameOver) { // ゲームオーバー中は更新しない
                if (deltaTime <= 0) return;
                if (deltaTime > 0.1f) deltaTime = 0.1f;
                velocity.add(0, GRAVITY);
                velocity.x *= DAMPING;
                velocity.x = MathUtils.clamp(velocity.x, -MAX_VELOCITY, MAX_VELOCITY);
                if (Math.abs(velocity.x) < 0.1f) {
                    velocity.x = 0;
                }
                velocity.scl(deltaTime);
            }
        }
    }

    static class Bullet {
        static final float SPEED = 15f;
        static final float LIFETIME = 1f;
        static final float SIZE = 0.2f;

        Vector2 position = new Vector2();
        Vector2 velocity = new Vector2();
        float lifeTime = 0;
        boolean active = true;
        int ownerId; // 発射したプレイヤーのID

        public Bullet() {}

        public void init(float x, float y, float angle, int ownerId) {
            position.set(x, y);
            velocity.set(MathUtils.cosDeg(angle) * SPEED, MathUtils.sinDeg(angle) * SPEED);
            lifeTime = 0;
            active = true;
            this.ownerId = ownerId;
        }
        public void update(float deltaTime) {
            position.add(velocity.x * deltaTime, velocity.y * deltaTime);
            lifeTime += deltaTime;
            if (lifeTime > LIFETIME) {
                active = false;
            }
        }
    }

    // --- マップ／レンダリング関連 ---
    private TiledMap map;
    private OrthogonalTiledMapRenderer renderer;
    private OrthographicCamera camera;
    private Player player1;
    private Map<Integer, Player> otherPlayers = new HashMap<>();

    private Array<Bullet> bullets = new Array<>();
    private Pool<Bullet> bulletPool = new Pool<Bullet>() {
        @Override
        protected Bullet newObject() {
            return new Bullet();
        }
    };

    private Pool<Rectangle> rectPool = new Pool<Rectangle>() {
        @Override
        protected Rectangle newObject() {
            return new Rectangle();
        }
    };
    private Array<Rectangle> tiles = new Array<>();

    private static final float GRAVITY = -2.5f;

    private boolean debug = false;
    private ShapeRenderer debugRenderer;

    private SpriteBatch spriteBatch;
    private BitmapFont font;
    private GlyphLayout glyphLayout = new GlyphLayout();

    // --- WebSocket クライアント ---
    private WebSocketClient client; // 事前に実装済みの WebSocketClient を利用してください
    private int myPlayerId = -1; // このクライアントのプレイヤーID
    private int opponentPlayerId = -1;  //対戦相手のid
    private String gameResultText = "";

    @Override
    public void create() {
        // プレイヤーサイズ等の初期化
        Player.WIDTH = 1 / 2f;
        Player.HEIGHT = 1f;

        map = new TmxMapLoader().load("./map.tmx");
        renderer = new OrthogonalTiledMapRenderer(map, 1 / 16f);

        camera = new OrthographicCamera();
        camera.setToOrtho(false, 30, 20);
        camera.update();

        // ローカルプレイヤー作成（IDはサーバーから受信後に更新）
        player1 = new Player(1);
        player1.position.set(10, 10);

        debugRenderer = new ShapeRenderer();
        spriteBatch = new SpriteBatch();
        font = new BitmapFont();
        font.getData().setScale(0.1f);
        font.getRegion().getTexture().setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        font.setColor(Color.BLACK);

        // InputProcessor は自分で実装（Scene2D は使用しない）
        Gdx.input.setInputProcessor(this);

        // WebSocket 接続
        connectTogameServer();
        try {
            System.out.println("1.5秒待ちます...");
            Thread.sleep(1500); // 1.5秒待機 for connection to establish
            System.out.println("待機が完了しました！");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void connectTogameServer() {
        String uri = "wss://taiko.choko.cc/test"; // サーバーアドレスに置き換えてください
        Consumer<String> messageHandler = this::handleServerMessage;
        client = new WebSocketClient(uri, "proxy.daisho.ac.jp", 8080, messageHandler); // proxy settings removed for local server
        try {
            client.connect();
        } catch (IOException e) {
            Gdx.app.error("WebSocket", "gameサーバー接続失敗: " + e.getMessage());
        }
    }


    private void handleServerMessage(String message) {
        String[] parts = message.split("\\|");
        String command = parts[0];
        switch (command) {
            case "ID": {
                myPlayerId = Integer.parseInt(parts[1]);
                player1.playerNumber = myPlayerId;
                Gdx.app.log("WebSocket", "自分のプレイヤーID: " + myPlayerId);
                sendPlayerUpdate();
                // 自動マッチング開始
                String message2 = "FIND_MATCH";
                try {
                    client.send(message2);
                    Gdx.app.log("WebSocket", "自動マッチング開始要求送信");
                } catch (IOException e) {
                    Gdx.app.error("WebSocket", "マッチング送信失敗: " + e.getMessage());
                }
                break;
            }
            case "NEW_PLAYER": {
                int newPlayerId = Integer.parseInt(parts[1]);
                if (newPlayerId == myPlayerId) break;
                float x = Float.parseFloat(parts[2]);
                float y = Float.parseFloat(parts[3]);
                Player newPlayer = new Player(newPlayerId);
                newPlayer.position.set(x, y);
                newPlayer.velocity.set(0, 0);
                otherPlayers.put(newPlayerId, newPlayer);
                Gdx.app.log("WebSocket", "新規プレイヤー接続: " + newPlayerId);
                break;
            }
            case "PLAYER_MOVED": {
                int playerId = Integer.parseInt(parts[1]);
                float x = Float.parseFloat(parts[2]);
                float y = Float.parseFloat(parts[3]);
                float velX = Float.parseFloat(parts[4]);
                float velY = Float.parseFloat(parts[5]);
                float gunAngle = Float.parseFloat(parts[6]);
                if (playerId == myPlayerId) break;
                Player otherPlayer = otherPlayers.get(playerId);
                if (otherPlayer == null) {
                    otherPlayer = new Player(playerId);
                    otherPlayers.put(playerId, otherPlayer);
                }
                otherPlayer.position.set(x, y);
                otherPlayer.velocity.set(velX, velY);
                otherPlayer.gunAngle = gunAngle;
                break;
            }
            case "PLAYER_HIT": {
                int playerId = Integer.parseInt(parts[1]);
                float newDamage = Float.parseFloat(parts[2]);
                float knockbackX = Float.parseFloat(parts[3]);
                float knockbackY = Float.parseFloat(parts[4]);
                if (playerId == myPlayerId) {
                    player1.damage = newDamage;
                    player1.velocity.set(knockbackX, knockbackY);
                } else {
                    Player otherPlayer = otherPlayers.get(playerId);
                    if (otherPlayer != null) {
                        otherPlayer.damage = newDamage;
                        otherPlayer.velocity.set(knockbackX, knockbackY);
                    }
                }
                break;
            }
            case "PLAYER_DISCONNECTED": {
                int playerId = Integer.parseInt(parts[1]);
                otherPlayers.remove(playerId);
                Gdx.app.log("WebSocket", "プレイヤー切断: " + playerId);
                break;
            }
            case "BULLET_FIRED": {
                int playerId = Integer.parseInt(parts[1]);
                float x = Float.parseFloat(parts[2]);
                float y = Float.parseFloat(parts[3]);
                float angle = Float.parseFloat(parts[4]);
                Bullet bullet = bulletPool.obtain();
                bullet.init(x, y, angle, playerId);
                bullets.add(bullet);
                break;
            }
            case "GAME_OVER": {
                Gdx.app.exit(); // ゲーム終了処理はクライアント側で単純にアプリケーションを終了する
                break;
            }
            case "PLAYER_FELL": {
                int playerId = Integer.parseInt(parts[1]);
                int deaths = Integer.parseInt(parts[2]); // サーバーから送られてきたデス数
                if (playerId == myPlayerId) {
                    player1.deaths = deaths; // デス数を更新
                } else {
                    Player otherPlayer = otherPlayers.get(playerId);
                    if (otherPlayer != null) {
                        otherPlayer.deaths = deaths; // デス数を更新
                    }
                }
                break;
            }
            case "PLAYER_RESPAWNED": {
                int playerId = Integer.parseInt(parts[1]);
                float x = Float.parseFloat(parts[2]);
                float y = Float.parseFloat(parts[3]);
                if (playerId == myPlayerId) {
                    player1.position.set(x, y); // リスポーン位置に設定
                    player1.velocity.set(0, 0); // 速度リセット
                } else {
                    Player otherPlayer = otherPlayers.get(playerId);
                    if (otherPlayer != null) {
                        otherPlayer.position.set(x, y); // リスポーン位置に設定
                        otherPlayer.velocity.set(0, 0); // 速度リセット
                    }
                }
                break;
            }
            // サーバーからルーム参加／マッチング成立の応答が来た場合、ゲーム状態を PLAYING に切替
            case "JOINED_ROOM":
            case "ROOM_CREATED":
            case "MATCH_FOUND": {
                gameState = GameState.PLAYING;
                Gdx.app.log("WebSocket", "ゲーム開始: " + message);
                break;
            }
            default:
                Gdx.app.log("WebSocket", "不明なコマンド: " + command);
                break;
        }
    }


    private void sendPlayerUpdate() {
        if (client != null && client.isConnected() && myPlayerId != -1) {
            String message = String.format("UPDATE|%d|%f|%f|%f|%f|%f", myPlayerId,
                    player1.position.x, player1.position.y,
                    player1.velocity.x, player1.velocity.y, player1.gunAngle);
            try {
                client.send(message);
            } catch (IOException e) {
                Gdx.app.error("WebSocket", "アップデート送信失敗: " + e.getMessage());
            }
        }
    }

    private void sendBulletFired(float x, float y, float angle) {
        if (client != null && client.isConnected() && myPlayerId != -1 && gameState == GameState.PLAYING) { // ゲームプレイ中のみ発射可能
            String message = String.format("FIRE|%d|%f|%f|%f", myPlayerId, x, y, angle);
            try {
                client.send(message);
            } catch (IOException e) {
                Gdx.app.error("WebSocket", "弾発射送信失敗: " + e.getMessage());
            }
        }
    }

    @Override
    public void render() {
        float deltaTime = Gdx.graphics.getDeltaTime();

        // ロビー状態の場合は背景と説明テキストのみ描画
        if (gameState == GameState.LOBBY) {
            ScreenUtils.clear(0, 0, 0, 1);
            spriteBatch.begin();
            String instructions = "【ルビー画面】\n" +
                                  "Mキー：マッチング\n" +
                                  "※サーバーからの応答でゲーム開始します";
            glyphLayout.setText(font, instructions);
            font.draw(spriteBatch, instructions,
                    (Gdx.graphics.getWidth() - glyphLayout.width) / 2,
                    (Gdx.graphics.getHeight() + glyphLayout.height) / 2);
            spriteBatch.end();
            return;
        }

        // ゲームオーバー画面
        if (gameState == GameState.GAME_OVER_SCREEN) {
            ScreenUtils.clear(0, 0, 0, 1);
            spriteBatch.begin();
            String gameOverDisplay = "ゲーム終了\n" + gameResultText + "\nQキー: リロビー";
            glyphLayout.setText(font, gameOverDisplay);
            font.draw(spriteBatch, gameOverDisplay,
                    (Gdx.graphics.getWidth() - glyphLayout.width) / 2,
                    (Gdx.graphics.getHeight() + glyphLayout.height) / 2);
            spriteBatch.end();
            return;
        }

        // ゲームプレイ中の処理
        ScreenUtils.clear(0.7f, 0.7f, 1.0f, 1);

        // ローカルプレイヤーの入力・更新
        player1.handleInput(deltaTime, myPlayerId);
        player1.update(deltaTime);
        updatePlayer(player1, deltaTime);
        checkPlayerBulletCollision(player1);

        // 弾の更新
        updateBullets(deltaTime);

        // 他のプレイヤーの更新
        for (Player otherPlayer : otherPlayers.values()) {
            otherPlayer.update(deltaTime);
            updatePlayer(otherPlayer, deltaTime);
            checkPlayerBulletCollision(otherPlayer);
        }

        // レンダリング
        camera.position.x = player1.position.x;
        camera.update();
        renderer.setView(camera);
        renderer.render();

        renderPlayer(player1, deltaTime);
        for (Player otherPlayer : otherPlayers.values()) {
            renderPlayer(otherPlayer, deltaTime);
        }
        renderBullets();
        if (debug) renderDebug();
        sendPlayerUpdate();
    }

    private void updatePlayer(Player player, float deltaTime) {
        if (deltaTime <= 0) return;
        if (deltaTime > 0.1f) deltaTime = 0.1f;

        if (player.playerNumber == myPlayerId) {
            Vector3 mousePos = camera.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));
            player.gunAngle = MathUtils.atan2(
                    mousePos.y - (player.position.y + Player.HEIGHT / 2),
                    mousePos.x - (player.position.x + Player.WIDTH / 2)
            ) * MathUtils.radiansToDegrees;
        }

        Rectangle playerRect = rectPool.obtain();
        playerRect.set(player.position.x, player.position.y, Player.WIDTH, Player.HEIGHT);
        int startX, startY, endX, endY;
        if (player.velocity.x > 0) {
            startX = endX = (int)(player.position.x + Player.WIDTH + player.velocity.x);
        } else {
            startX = endX = (int)(player.position.x + player.velocity.x);
        }
        startY = (int)player.position.y;
        endY = (int)(player.position.y + Player.HEIGHT);


        getTiles(startX, startY, endX, endY, tiles);
        playerRect.x += player.velocity.x;
        for (Rectangle tile : tiles) {
            if (playerRect.overlaps(tile)) {
                player.velocity.x = 0;
                break;
            }
        }
        playerRect.x = player.position.x;

        if (player.velocity.y > 0) {
            startY = endY = (int)(player.position.y + Player.HEIGHT + player.velocity.y);
        } else {
            startY = endY = (int)(player.position.y + player.velocity.y);
        }
        startX = (int)player.position.x;
        endX = (int)(player.position.x + Player.WIDTH);
        getTiles(startX, startY, endX, endY, tiles);
        playerRect.y += player.velocity.y;
        for (Rectangle tile : tiles) {
            if (playerRect.overlaps(tile)) {
                if (player.velocity.y > 0) {
                    player.position.y = tile.y - Player.HEIGHT;
                } else {
                    player.position.y = tile.y + tile.height;
                    player.grounded = true;
                }
                player.velocity.y = 0;
                break;
            }
        }
        rectPool.free(playerRect);
        player.position.add(player.velocity);
        player.velocity.scl(1 / deltaTime);

        if (player.position.y < -5 || player.position.y > 30) { // 落下判定をクライアント側でも簡易的に行う (サーバー側が主導)
            if (player.playerNumber == myPlayerId && gameState == GameState.PLAYING) { // 自分のプレイヤーのみ、ゲームプレイ中のみ
                try {
                    client.send("FELL|" + myPlayerId); // サーバーに落下を通知
                } catch (IOException e) {
                    Gdx.app.error("WebSocket", "落下通知送信失敗: " + e.getMessage());
                }
            }
        }

        if (isPlayerEmbedded(player)) {
            teleportPlayerToSurface(player);
        }
    }

    private boolean isPlayerEmbedded(Player player) {
        return isPlayerEmbedded(player, "walls"); // wallsレイヤーに対してのみ判定
    }

    private boolean isPlayerEmbedded(Player player, String layerName) {
        Array<Rectangle> collisionTiles = new Array<>();
        int startX = (int)player.position.x;
        int startY = (int)player.position.y;
        int endX = (int)(player.position.x + Player.WIDTH);
        int endY = (int)(player.position.y + Player.HEIGHT);
        getTiles(startX, startY, endX, endY, collisionTiles, layerName); // 特定のレイヤーを指定
        Rectangle playerRect = new Rectangle(player.position.x, player.position.y, Player.WIDTH, Player.HEIGHT);
        boolean embedded = false;
        for (Rectangle tile : collisionTiles) {
            if (playerRect.overlaps(tile)) {
                embedded = true;
                break;
            }
        }
        rectPool.freeAll(collisionTiles);
        collisionTiles.clear();
        return embedded;
    }


    private void teleportPlayerToSurface(Player player) {
        float initialY = player.position.y;
        int iterations = 0;
        while (isPlayerEmbedded(player) && iterations < 100) {
            player.position.y += 0.1f;
            iterations++;
        }
        if (isPlayerEmbedded(player)) {
            Gdx.app.log("Teleport", "テレポート失敗: プレイヤー " + player.playerNumber);
        } else {
            Gdx.app.log("Teleport", "プレイヤー " + player.playerNumber + " を y=" + initialY + " から y=" + player.position.y + " にテレポート");
            player.grounded = true;
        }
    }

    private void updateBullets(float deltaTime) {
        Iterator<Bullet> iter = bullets.iterator();
        while (iter.hasNext()) {
            Bullet bullet = iter.next();
            bullet.update(deltaTime);
            if (!bullet.active) {
                bulletPool.free(bullet);
                iter.remove();
            } else {
                Rectangle bulletRect = new Rectangle(bullet.position.x, bullet.position.y, Bullet.SIZE, Bullet.SIZE);
                int startX = (int)bullet.position.x;
                int startY = (int)bullet.position.y;
                int endX = (int)(bullet.position.x + Bullet.SIZE);
                int endY = (int)(bullet.position.y + Bullet.SIZE);
                getTiles(startX, startY, endX, endY, tiles, "walls"); // wallsレイヤーのみ判定
                for (Rectangle tile : tiles) {
                    if (bulletRect.overlaps(tile)) {
                        bullet.active = false;
                        TiledMapTileLayer layer = (TiledMapTileLayer)map.getLayers().get("walls");
                        if (layer != null && layer.getCell((int)tile.x, (int)tile.y) != null) {
                            // 必要ならタイルを除去する処理
                        }
                        TiledMapTileLayer breakableLayer = (TiledMapTileLayer)map.getLayers().get("breakable");
                        if (breakableLayer != null && breakableLayer.getCell((int)tile.x, (int)tile.y) != null) {
                            breakableLayer.setCell((int)tile.x, (int)tile.y, null);
                        }
                        break;
                    }
                }
                tiles.clear();
            }
        }
    }

    private Rectangle getPlayerBounds(Player player) {
        return new Rectangle(player.position.x, player.position.y, Player.WIDTH, Player.HEIGHT);
    }

    private void checkPlayerBulletCollision(Player player) {
        Rectangle playerRect = getPlayerBounds(player);
        Iterator<Bullet> bulletIterator = bullets.iterator();
        while (bulletIterator.hasNext()) {
            Bullet bullet = bulletIterator.next();
            if (bullet.active && bullet.ownerId != player.playerNumber) {
                Rectangle bulletRect = new Rectangle(bullet.position.x, bullet.position.y, Bullet.SIZE, Bullet.SIZE);
                if (playerRect.overlaps(bulletRect)) {
                    Gdx.app.log("Collision", "Player " + player.playerNumber + " hit by bullet from " + bullet.ownerId);
                    bullet.active = false;
                    bulletPool.free(bullet);
                    bulletIterator.remove();
                }
            }
        }
    }

    private void getTiles(int startX, int startY, int endX, int endY, Array<Rectangle> tiles, String layerName) {
        TiledMapTileLayer layer = (TiledMapTileLayer)map.getLayers().get(layerName);
        rectPool.freeAll(tiles);
        tiles.clear();
        if (layer != null) {
            for (int y = startY; y <= endY; y++) {
                for (int x = startX; x <= endX; x++) {
                    Cell cell = layer.getCell(x, y);
                    if (cell != null && cell.getTile().getId() != 0) {

                        int tileId = cell.getTile().getId();
                        int numSlices = 8;
                        float sliceWidth = 1f / numSlices;
                        if (tileId == 7) {
                            for (int i = 0; i < numSlices; i++) {
                                float sliceHeight = 0.5f * ((i + 1f) / numSlices);
                                Rectangle subRect = rectPool.obtain();
                                subRect.set(x + i * sliceWidth, y, sliceWidth, sliceHeight);
                                tiles.add(subRect);
                            }
                        } else if (tileId == 8) {
                            for (int i = 0; i < numSlices; i++) {
                                float sliceHeight = 0.5f * ((i + 1f) / numSlices);
                                Rectangle subRect = rectPool.obtain();
                                subRect.set(x + i * sliceWidth, y + 0.5f, sliceWidth, sliceHeight);
                                tiles.add(subRect);
                            }
                        } else if (tileId == 10) {
                            for (int i = 0; i < numSlices; i++) {
                                float sliceHeight = 0.5f * (1 - ((i + 1f) / numSlices));
                                Rectangle subRect = rectPool.obtain();
                                subRect.set(x + i * sliceWidth, y + 0.5f, sliceWidth, sliceHeight);
                                tiles.add(subRect);
                            }
                        } else if (tileId == 11) {
                            for (int i = 0; i < numSlices; i++) {
                                float sliceHeight = 0.5f * (1 - ((i + 1f) / numSlices));
                                Rectangle subRect = rectPool.obtain();
                                subRect.set(x + i * sliceWidth, y, sliceWidth, sliceHeight);
                                tiles.add(subRect);
                            }
                        } else {
                            Rectangle rect = rectPool.obtain();
                            rect.set(x, y, 1, 1);
                            tiles.add(rect);
                        }
                    }
                }
            }
        }
    }

    private void getTiles(int startX, int startY, int endX, int endY, Array<Rectangle> tiles) {
        getTiles(startX, startY, endX, endY, tiles, "walls"); // デフォルトで"walls"レイヤーを使用
    }


    private void renderDebug() {
        debugRenderer.setProjectionMatrix(camera.combined);
        debugRenderer.begin(ShapeType.Line);
        debugRenderer.setColor(Color.RED);
        debugRenderer.rect(player1.position.x, player1.position.y, Player.WIDTH, Player.HEIGHT);
        debugRenderer.setColor(Color.YELLOW);
        TiledMapTileLayer layer = (TiledMapTileLayer)map.getLayers().get("walls");
        if (layer != null) {
            int numSlices = 8;
            float sliceWidth = 1f / numSlices;
            for (int y = 0; y < layer.getHeight(); y++) {
                for (int x = 0; x < layer.getWidth(); x++) {
                    Cell cell = layer.getCell(x, y);
                    if (cell != null) {
                        int tileId = cell.getTile().getId();
                        if (tileId == 7) {
                            for (int i = 0; i < numSlices; i++) {
                                float sliceHeight = 0.5f * ((i + 1f) / numSlices);
                                if (camera.frustum.boundsInFrustum(x + i * sliceWidth + sliceWidth / 2, y + sliceHeight / 2, 0, sliceWidth, sliceHeight, 0))
                                    debugRenderer.rect(x + i * sliceWidth, y, sliceWidth, sliceHeight);
                            }
                        } else if (tileId == 8) {
                            for (int i = 0; i < numSlices; i++) {
                                float sliceHeight = 0.5f * ((i + 1f) / numSlices);
                                if (camera.frustum.boundsInFrustum(x + i * sliceWidth + sliceWidth / 2, y + 0.5f + sliceHeight / 2, 0, sliceWidth, sliceHeight, 0))
                                    debugRenderer.rect(x + i * sliceWidth, y + 0.5f, sliceWidth, sliceHeight);
                            }
                        } else if (tileId == 10) {
                            for (int i = 0; i < numSlices; i++) {
                                float sliceHeight = 0.5f * (1 - ((i + 1f) / numSlices));
                                if (camera.frustum.boundsInFrustum(x + i * sliceWidth + sliceWidth / 2, y + 0.5f + sliceHeight / 2, 0, sliceWidth, sliceHeight, 0))
                                    debugRenderer.rect(x + i * sliceWidth, y + 0.5f, sliceWidth, sliceHeight);
                            }
                        } else if (tileId == 11) {
                            for (int i = 0; i < numSlices; i++) {
                                float sliceHeight = 0.5f * (1 - ((i + 1f) / numSlices));
                                if (camera.frustum.boundsInFrustum(x + i * sliceWidth + sliceWidth / 2, y + sliceHeight / 2, 0, sliceWidth, sliceHeight, 0))
                                    debugRenderer.rect(x + i * sliceWidth, y, sliceWidth, sliceHeight);
                            }
                        } else {
                            if (camera.frustum.boundsInFrustum(x + 0.5f, y + 0.5f, 0, 1, 1, 0))
                                debugRenderer.rect(x, y, 1, 1);
                        }
                    }
                }
            }
        }
        debugRenderer.end();
    }

    private void renderPlayer(Player player, float deltaTime) {
        debugRenderer.setProjectionMatrix(camera.combined);
        debugRenderer.begin(ShapeType.Filled);
        if (player.playerNumber == myPlayerId) {
            debugRenderer.setColor(Color.RED);
        } else {
            debugRenderer.setColor(Color.BLUE);
        }
        float playerX = player.position.x;
        float playerY = player.position.y;
        float width = Player.WIDTH;
        float height = Player.HEIGHT;
        float headRadius = width / 2f;
        float headCenterX = playerX + width / 2f;
        float headCenterY = playerY + height - headRadius;
        debugRenderer.circle(headCenterX, headCenterY, headRadius);
        float bodyStartY = headCenterY - headRadius;
        float bodyEndY = playerY + height * 0.3f;
        debugRenderer.rectLine(headCenterX, bodyStartY, headCenterX, bodyEndY, width / 8f);
        float armLength = width * 0.7f;
        float armY = bodyStartY - height * 0.2f;
        float gunLength = 0.4f;
        float gunEndX = headCenterX + MathUtils.cosDeg(player.gunAngle) * gunLength;
        float gunEndY = armY + MathUtils.sinDeg(player.gunAngle) * gunLength;
        debugRenderer.rectLine(headCenterX, armY, gunEndX, gunEndY, width / 16f);
        debugRenderer.rectLine(headCenterX, armY, headCenterX - armLength / 2f, armY - height * 0.1f, width / 16f);
        float legLength = height * 0.4f;
        float legSeparation = width * 0.1f;
        debugRenderer.rectLine(headCenterX - legSeparation, bodyEndY, headCenterX - legSeparation - width * 0.1f, bodyEndY - legLength, width / 16f);
        debugRenderer.rectLine(headCenterX + legSeparation, bodyEndY, headCenterX + legSeparation + width * 0.1f, bodyEndY - legLength, width / 16f);
        debugRenderer.end();

        spriteBatch.setProjectionMatrix(camera.combined);
        spriteBatch.begin();
        String damageText = String.format("%.0f%%", player.damage);
        GlyphLayout layoutDamage = new GlyphLayout();
        layoutDamage.setText(font, damageText);
        float textWidthDamage = layoutDamage.width;
        font.draw(spriteBatch, damageText, headCenterX - textWidthDamage / 2f, headCenterY + headRadius + 1f);


        spriteBatch.end();
    }

    private void renderBullets() {
        debugRenderer.setProjectionMatrix(camera.combined);
        debugRenderer.begin(ShapeType.Filled);
        debugRenderer.setColor(Color.BLACK);
        for (Bullet bullet : bullets) {
            debugRenderer.circle(bullet.position.x, bullet.position.y, Bullet.SIZE);
        }
        debugRenderer.end();
    }

    @Override
    public void dispose() {
        debugRenderer.dispose();
        renderer.dispose();
        map.dispose();
        spriteBatch.dispose();
        font.dispose();
        if (client != null) {
            client.close();
        }
    }

    // InputProcessor 実装：ロビー中は特定キーで UI 処理、ゲーム中は通常操作
    @Override
    public boolean keyDown(int keycode) {
        // ロビー状態であれば

        // ゲーム中の入力は各処理で行う
        if (keycode == Keys.Q) {
            debug = !debug;
            if (gameState == GameState.GAME_OVER_SCREEN) {
                gameState = GameState.LOBBY; // ゲームオーバー画面からロビーに戻る
                gameResultText = ""; // 結果テキストをクリア
                player1.deaths = 0; // デス数リセット
                player1.gameOver = false;
                otherPlayers.clear(); // 他プレイヤー情報クリア
                bullets.clear(); // 弾丸クリア
                myPlayerId = -1; // プレイヤーIDをリセット (サーバーから再取得するため)
                opponentPlayerId = -1; // 対戦相手IDリセット
                connectTogameServer(); // サーバーに再接続して新しいIDを取得
            }
            return true;
        }
        return false;
    }

    @Override public boolean keyUp(int keycode) { return false; }
    @Override public boolean keyTyped(char character) { return false; }
    @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button == 0 && myPlayerId != -1 && gameState == GameState.PLAYING && !player1.gameOver) { // ゲームプレイ中、ゲームオーバーでない時のみ発射可能
            float gunLength = 0.4f;
            float fireX = player1.position.x + Player.WIDTH / 2f + MathUtils.cosDeg(player1.gunAngle) * gunLength;
            float fireY = player1.position.y + Player.HEIGHT / 2f + MathUtils.sinDeg(player1.gunAngle) * gunLength;
            sendBulletFired(fireX, fireY, player1.gunAngle);
        }
        return true;
    }
    @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) { return false; }
    @Override public boolean touchDragged(int screenX, int screenY, int pointer) { return false; }
    @Override public boolean mouseMoved(int screenX, int screenY) { return false; }
    @Override public boolean scrolled(float amountX, float amountY) { return false; }
    @Override public void pause() { }
    @Override public void resume() { }
    @Override public void resize(int width, int height) { }

    public static void main(String[] arg) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setForegroundFPS(60);
        config.setTitle("Game");
        config.setWindowedMode(1200, 800);
        config.setMaximized(true); // 最大化設定
        new Lwjgl3Application(new Game(), config);
    }

	@Override
	public boolean touchCancelled(int arg0, int arg1, int arg2, int arg3) {
		// TODO 自動生成されたメソッド・スタブ
		return false;
	}
}