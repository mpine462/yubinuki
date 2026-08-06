import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.*;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.*;

import javax.imageio.ImageIO;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public class App extends Application {

    private Image originalImage;
    private WritableImage workingImage;
    private String currentPatternName;
    private final Deque<WritableImage> undoStack = new ArrayDeque<>();
    private double tolerance = 0.1; // 判定幅（0〜1）

    // Undo履歴の上限。
    private static final int MAX_UNDO_HISTORY = 20;

    // バージョン管理　保存スロット
    private static final int VERSION_SLOT_COUNT = 3;
    private final WritableImage[] versionSlots = new WritableImage[VERSION_SLOT_COUNT];
    private final String[] versionSlotTimes = new String[VERSION_SLOT_COUNT];
    private int nextVersionSlot = 0; // 次にどのスロットへ保存するか（リングバッファ式）
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // レイアウトの余白
    private static final double HORIZONTAL_MARGIN = 60;
    private static final double VERTICAL_MARGIN = 80;
    private static final double MIN_IMAGE_SIZE = 80; // 下限

    /**
     * 1つのバージョンスロットに紐づくUI部品をまとめたクラス。
     * サムネイル・枠(クリック領域)・ツールチップを1セットで扱えるようにする。
     */
    private static final class VersionSlotUI {
        final ImageView thumbnail;
        final StackPane frame;
        final Tooltip tooltip;

        VersionSlotUI(ImageView thumbnail, StackPane frame, Tooltip tooltip) {
            this.thumbnail = thumbnail;
            this.frame = frame;
            this.tooltip = tooltip;
        }
    }

    @Override
    public void start(Stage stage) {
        StackPane rootPane = new StackPane();

        // ===== ホーム画面 =====
        Label label = new Label("加賀指ぬきデザインツール");
        label.setFont(new Font(24));

        Button button1 = new Button("新規作成");
        Button button2 = new Button("読み込み");
        button1.getStyleClass().add("button-style");
        button2.getStyleClass().add("button-style");

        ContextMenu patternMenu = new ContextMenu();
        Label label2 = new Label("四つ鱗");
        Label label3 = new Label("市松");
        Label label4 = new Label("青海波");
        label2.getStyleClass().add("custom-menu-label");
        label3.getStyleClass().add("custom-menu-label");
        label4.getStyleClass().add("custom-menu-label");

        MenuItem item1 = new MenuItem(); item1.setGraphic(label2);
        MenuItem item2 = new MenuItem(); item2.setGraphic(label3);
        MenuItem item3 = new MenuItem(); item3.setGraphic(label4);
        patternMenu.getItems().addAll(item1, item2, item3);

        button1.setOnAction(e -> patternMenu.show(button1, Side.BOTTOM, 0, 0));

        ContextMenu loadMenu = new ContextMenu();
        MenuItem loadFile = new MenuItem("画像読み込み");
        MenuItem loadClipboard = new MenuItem("クリップボードから");
        loadMenu.getItems().addAll(loadFile, loadClipboard);
        button2.setOnAction(e -> loadMenu.show(button2, Side.BOTTOM, 0, 0));

        // ホーム画面のボタンはHBoxで中央寄せにし、どんなウィンドウサイズでも自動的に収まるようにする
        HBox homeButtonBox = new HBox(40, button1, button2);
        homeButtonBox.setAlignment(Pos.CENTER);
        VBox homeCenterBox = new VBox(homeButtonBox);
        homeCenterBox.setAlignment(Pos.CENTER);

        BorderPane homeView = new BorderPane(homeCenterBox);
        homeView.setTop(label);
        BorderPane.setAlignment(label, Pos.CENTER);
        BorderPane.setMargin(label, new Insets(50, 0, 20, 0));

        // ===== 編集画面 =====
        BorderPane editView = new BorderPane();

        Button cancelButton = new Button("中止");
        cancelButton.getStyleClass().add("button-style");

        Button saveButton = new Button("保存");
        saveButton.getStyleClass().add("button-style");

        Button undoButton = new Button("戻す");
        undoButton.getStyleClass().add("button-style");
        undoButton.setDisable(true);

        // スライダー（色判定幅調整）
        Label toleranceLabel = new Label("判定幅: 0.10");
        Slider toleranceSlider = new Slider(0, 1, 0.1);
        toleranceSlider.setShowTickLabels(true);
        toleranceSlider.setShowTickMarks(true);
        toleranceSlider.setMajorTickUnit(0.25);
        toleranceSlider.setPrefWidth(150);
        toleranceSlider.setMaxWidth(200);
        toleranceSlider.valueProperty().addListener((obs, oldV, newV) -> {
            tolerance = newV.doubleValue();
            toleranceLabel.setText(String.format("判定幅: %.2f", tolerance));
        });

        // topBarはToolBarにする → 画面幅が足りない時は自動で「>>」の
        // オーバーフローメニューに収納され、はみ出さない
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar topBar = new ToolBar(cancelButton, toleranceLabel, toleranceSlider, spacer, undoButton, saveButton);
        topBar.setPadding(new Insets(5, 10, 5, 10));
        editView.setTop(topBar);

        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        Color[] originalColors = {
                Color.web("#6373FF"),
                Color.web("#63FFAC"),
                Color.web("#DCFF63"),
                Color.web("#FF6395")
        };

        // ColorPickerはここでは生成のみ行い、onActionは editingControls 構築後にまとめて設定する
        ColorPicker[] colorPickers = new ColorPicker[4];
        for (int i = 0; i < 4; i++) {
            colorPickers[i] = new ColorPicker(originalColors[i]);
        }

        String[] texts = {"色１", "色２", "色３", "色４"};
        List<Label> colorLabels = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            Label colorLabel = new Label(texts[i]);
            colorLabel.getStyleClass().addAll("color-label", "color" + (i + 1));
            colorLabels.add(colorLabel);
        }

        Button resetButton = new Button("色リセット");
        resetButton.getStyleClass().add("resetButton-style");

        GridPane colorGrid = new GridPane();
        colorGrid.setHgap(10);
        colorGrid.setVgap(10);
        colorGrid.setAlignment(Pos.CENTER);
        colorGrid.add(resetButton, 0, 0);
        colorGrid.add(colorLabels.get(0), 0, 1);
        colorGrid.add(colorPickers[0], 1, 1);
        colorGrid.add(colorLabels.get(1), 0, 2);
        colorGrid.add(colorPickers[1], 1, 2);
        colorGrid.add(colorLabels.get(2), 2, 1);
        colorGrid.add(colorPickers[2], 3, 1);
        colorGrid.add(colorLabels.get(3), 2, 2);
        colorGrid.add(colorPickers[3], 3, 2);

        // ===== バージョン管理（保存スロット）UI =====
        Button saveVersionButton = new Button("現在の状態を保存");
        saveVersionButton.getStyleClass().add("button-style");

        VersionSlotUI[] versionSlotUIs = new VersionSlotUI[VERSION_SLOT_COUNT];
        HBox thumbnailBar = new HBox(15);
        thumbnailBar.setAlignment(Pos.CENTER);

        for (int i = 0; i < VERSION_SLOT_COUNT; i++) {
            ImageView thumb = new ImageView();
            thumb.setFitWidth(100);
            thumb.setFitHeight(100);
            thumb.setPreserveRatio(true);
            thumb.setSmooth(true);

            Label slotLabel = new Label("Ver." + (i + 1));

            StackPane frame = new StackPane(thumb);
            frame.setPrefSize(100, 100);
            frame.setStyle("-fx-border-color: #999999; -fx-border-width: 1; -fx-background-color: #f0f0f0;");
            frame.setCursor(Cursor.DEFAULT);
            frame.setOpacity(0.5); // 未保存であることが視覚的にわかるよう半透明に

            Tooltip tooltip = new Tooltip("未保存");
            Tooltip.install(frame, tooltip);

            VBox slotBox = new VBox(5, frame, slotLabel);
            slotBox.setAlignment(Pos.CENTER);

            int slotIndex = i;
            frame.setOnMouseClicked(ev -> {
                if (versionSlots[slotIndex] == null) return; // 未保存のスロットは何もしない
                pushUndoState(undoButton);
                workingImage = deepCopy(versionSlots[slotIndex]);
                imageView.setImage(workingImage);
            });

            versionSlotUIs[i] = new VersionSlotUI(thumb, frame, tooltip);
            thumbnailBar.getChildren().add(slotBox);
        }

        saveVersionButton.setOnAction(e -> {
            if (workingImage == null) return;
            versionSlots[nextVersionSlot] = deepCopy(workingImage);
            versionSlotTimes[nextVersionSlot] = LocalTime.now().format(TIME_FORMAT);
            updateVersionSlotVisual(nextVersionSlot, versionSlotUIs);
            nextVersionSlot = (nextVersionSlot + 1) % VERSION_SLOT_COUNT;
        });

        VBox versionBox = new VBox(10, saveVersionButton, thumbnailBar);
        versionBox.setAlignment(Pos.CENTER);

        // colorGridとversionBoxをまとめておく（高さバインドで使うため）
        VBox controlsBox = new VBox(20, colorGrid, versionBox);
        controlsBox.setAlignment(Pos.CENTER);

        // ===== 編集操作の対象コントロール一覧 =====
        // 色置換などの重い処理を実行している間、誤操作を防ぐためにまとめて無効化する
        List<Node> editingControls = new ArrayList<>();
        editingControls.add(cancelButton);
        editingControls.add(saveButton);
        editingControls.add(undoButton);
        editingControls.addAll(Arrays.asList(colorPickers));
        editingControls.add(resetButton);
        editingControls.add(saveVersionButton);
        for (VersionSlotUI ui : versionSlotUIs) {
            editingControls.add(ui.frame);
        }

        // ===== 色ピッカーの動作（重い処理はバックグラウンドスレッドで実行） =====
        for (int i = 0; i < 4; i++) {
            int index = i;
            colorPickers[i].setOnAction(ev -> {
                pushUndoState(undoButton);
                replaceColorAsync(originalColors[index], colorPickers[index].getValue(),
                        imageView, editingControls, null);
            });
        }

        // ===== 色リセット（バルクコピーで高速化） =====
        resetButton.setOnAction(e -> {
            pushUndoState(undoButton);
            resetToOriginalColors(imageView);
            for (int i = 0; i < 4; i++) {
                colorPickers[i].setValue(originalColors[i]);
            }
        });

        VBox centerBox = new VBox(20, imageView, controlsBox);
        centerBox.setAlignment(Pos.CENTER);
        centerBox.setPadding(new Insets(20));

        // 画像＋各種コントロールをScrollPaneに入れる
        // → 動的リサイズ（下記）が効かないほど極端に小さい場合でも、
        //   スクロールで必ず全ツールにアクセスできるようにする保険
        ScrollPane centerScroll = new ScrollPane(centerBox);
        centerScroll.setFitToWidth(true);
        centerScroll.setFitToHeight(false);
        centerScroll.setPannable(true);
        centerScroll.getStyleClass().add("edge-to-edge");
        editView.setCenter(centerScroll);

        // ===== 保存ボタン（ファイル書き出し） =====
        saveButton.setOnAction(e -> {
            if (workingImage == null) return;
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("画像を保存");
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PNGファイル", "*.png")
            );
            File file = fileChooser.showSaveDialog(stage);
            if (file != null) {
                try {
                    BufferedImage bImage = SwingFXUtils.fromFXImage(workingImage, null);
                    ImageIO.write(bImage, "png", file);
                } catch (IOException ex) {
                    showError("画像の保存に失敗しました: " + ex.getMessage());
                }
            }
        });

        // ===== スポイト機能 =====
        imageView.setOnMouseClicked(e -> {
            if (workingImage == null) return;

            double imgWidth = workingImage.getWidth();
            double imgHeight = workingImage.getHeight();
            double viewWidth = imageView.getBoundsInLocal().getWidth();
            double viewHeight = imageView.getBoundsInLocal().getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;

            // fitWidth/fitHeightでスケーリングされている分を考慮して
            // クリック座標を実画像のピクセル座標に変換する
            double scaleX = imgWidth / viewWidth;
            double scaleY = imgHeight / viewHeight;

            double imgX = e.getX() * scaleX;
            double imgY = e.getY() * scaleY;

            if (imgX < 0 || imgY < 0 || imgX >= imgWidth || imgY >= imgHeight) return;

            Color clickedColor = workingImage.getPixelReader().getColor((int) imgX, (int) imgY);

            Popup popup = new Popup();
            ColorPicker picker = new ColorPicker(clickedColor);
            picker.setOnAction(ev -> {
                pushUndoState(undoButton);
                replaceColorAsync(clickedColor, picker.getValue(), imageView, editingControls, popup::hide);
            });

            popup.getContent().add(picker);
            popup.show(stage, e.getScreenX(), e.getScreenY() + 10);
        });

        // ===== Undo機能 =====
        undoButton.setOnAction(e -> {
            if (!undoStack.isEmpty()) {
                workingImage = undoStack.pop();
                imageView.setImage(workingImage);
            }
            undoButton.setDisable(undoStack.isEmpty());
        });

        // ===== 画面切替 =====
        rootPane.getChildren().addAll(homeView, editView);
        editView.setVisible(false);

        // ===== ウィンドウサイズに合わせて画像を自動リサイズ =====
        // マジックナンバーではなく、実際のtopBar・controlsBoxの高さを都度参照することで、
        // フォントサイズや内容が変わっても崩れないようにする。
        // Bindings.maxで下限を設け、極端に小さいウィンドウでも画像が消えないようにする。
        imageView.fitWidthProperty().bind(
                Bindings.max(MIN_IMAGE_SIZE, rootPane.widthProperty().subtract(HORIZONTAL_MARGIN))
        );
        imageView.fitHeightProperty().bind(
                Bindings.max(MIN_IMAGE_SIZE,
                        rootPane.heightProperty()
                                .subtract(topBar.heightProperty())
                                .subtract(controlsBox.heightProperty())
                                .subtract(VERTICAL_MARGIN))
        );

        // パターン読み込み
        item1.setOnAction(e -> {
            loadPatternImage("/resources/yotsuuroko.png", imageView, "yotsuuroko");
            startNewEditSession(homeView, editView, undoButton, versionSlotUIs);
        });
        item2.setOnAction(e -> {
            loadPatternImage("/resources/ichimatsu.png", imageView, "ichimatsu");
            startNewEditSession(homeView, editView, undoButton, versionSlotUIs);
        });
        item3.setOnAction(e -> {
            loadPatternImage("/resources/seigaiha.png", imageView, "seigaiha");
            startNewEditSession(homeView, editView, undoButton, versionSlotUIs);
        });

        loadFile.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("画像読み込み");
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg")
            );
            File file = fileChooser.showOpenDialog(stage);
            if (file != null) {
                try {
                    BufferedImage img = ImageIO.read(file);
                    if (img == null) {
                        showError("画像として読み込めないファイルです。");
                        return;
                    }
                    originalImage = SwingFXUtils.toFXImage(img, null);
                    workingImage = new WritableImage(originalImage.getPixelReader(),
                            (int) originalImage.getWidth(), (int) originalImage.getHeight());
                    imageView.setImage(workingImage);
                    startNewEditSession(homeView, editView, undoButton, versionSlotUIs);
                } catch (IOException ex) {
                    showError("画像の読み込みに失敗しました: " + ex.getMessage());
                }
            }
        });

        loadClipboard.setOnAction(e -> {
            try {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    showError("クリップボードに画像がありません。");
                    return;
                }
                java.awt.Image awtImage = (java.awt.Image) clipboard.getData(DataFlavor.imageFlavor);
                BufferedImage bufImg = new BufferedImage(
                        awtImage.getWidth(null),
                        awtImage.getHeight(null),
                        BufferedImage.TYPE_INT_ARGB
                );
                bufImg.getGraphics().drawImage(awtImage, 0, 0, null);
                originalImage = SwingFXUtils.toFXImage(bufImg, null);

                workingImage = new WritableImage(originalImage.getPixelReader(),
                        (int) originalImage.getWidth(), (int) originalImage.getHeight());

                imageView.setImage(workingImage);
                startNewEditSession(homeView, editView, undoButton, versionSlotUIs);
            } catch (Exception ex) {
                showError("クリップボードからの読み込みに失敗しました: " + ex.getMessage());
            }
        });

        cancelButton.setOnAction(e -> {
            if (!undoStack.isEmpty()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "編集中の内容は保存されていません。ホーム画面に戻りますか？",
                        ButtonType.OK, ButtonType.CANCEL);
                confirm.setHeaderText(null);
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    return;
                }
            }
            editView.setVisible(false);
            homeView.setVisible(true);
        });

        Scene scene = new Scene(rootPane, 1000, 600);
        stage.setTitle("加賀指ぬきデザインツール");
        stage.setScene(scene);

        // 極端に小さくなりすぎてカラーパレット(4列)が崩れないよう最小サイズを設定
        stage.setMinWidth(640);
        stage.setMinHeight(480);

        stage.show();
    }

    /**
     * 新しい画像/パターンを読み込んで編集画面へ切り替える際の共通処理。
     * Undo履歴とバージョンスロットを必ずクリアする（別プロジェクトの状態が残るバグを防ぐ）。
     */
    private void startNewEditSession(BorderPane homeView, BorderPane editView,
                                      Button undoButton, VersionSlotUI[] versionSlotUIs) {
        undoStack.clear();
        undoButton.setDisable(true);
        resetVersionSlots(versionSlotUIs);
        homeView.setVisible(false);
        editView.setVisible(true);
    }

    private void loadPatternImage(String path, ImageView imageView, String name) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new IOException("リソースが見つかりません: " + path);
            Image img = new Image(is);
            originalImage = img;
            workingImage = new WritableImage(img.getPixelReader(), (int) img.getWidth(), (int) img.getHeight());
            imageView.setImage(workingImage);
            currentPatternName = name;
        } catch (IOException e) {
            showError("柄の読み込みに失敗しました: " + e.getMessage());
        }
    }

    // Lab色空間変換
    private double[] rgbToLab(Color c) {
        double r = pivotRgb(c.getRed());
        double g = pivotRgb(c.getGreen());
        double b = pivotRgb(c.getBlue());

        double X = r * 0.4124 + g * 0.3576 + b * 0.1805;
        double Y = r * 0.2126 + g * 0.7152 + b * 0.0722;
        double Z = r * 0.0193 + g * 0.1192 + b * 0.9505;

        X /= 0.95047;
        Y /= 1.00000;
        Z /= 1.08883;

        X = pivotXYZ(X);
        Y = pivotXYZ(Y);
        Z = pivotXYZ(Z);

        double L = Math.max(0, (116 * Y) - 16);
        double a = 500 * (X - Y);
        double b2 = 200 * (Y - Z);

        return new double[]{L, a, b2};
    }

    private double pivotRgb(double n) {
        return (n <= 0.04045) ? n / 12.92 : Math.pow((n + 0.055) / 1.055, 2.4);
    }

    private double pivotXYZ(double n) {
        return (n > 0.008856) ? Math.cbrt(n) : (7.787 * n) + (16.0 / 116);
    }

    private double colorDiffLab(Color c1, Color c2) {
        double[] lab1 = rgbToLab(c1);
        double[] lab2 = rgbToLab(c2);
        double dl = lab1[0] - lab2[0];
        double da = lab1[1] - lab2[1];
        double db = lab1[2] - lab2[2];
        return Math.sqrt(dl * dl + da * da + db * db) / 100.0;
    }

    /**
     * 指定した色に近いピクセルを置換する。
     * Lab距離の計算（重い部分）だけをバックグラウンドスレッドで行い、
     * WritableImageへの実際のピクセル読み書き（PixelReader/PixelWriter）は
     * 必ずJavaFX Applicationスレッド上で行うことでスレッドセーフ性を保つ。
     */
    private void replaceColorAsync(Color target, Color replacement, ImageView imageView,
                                    List<Node> editingControls, Runnable onComplete) {
        if (workingImage == null) return;

        int w = (int) workingImage.getWidth();
        int h = (int) workingImage.getHeight();
        int[] pixels = new int[w * h];
        // 読み取りはFXスレッド上でバルク実行（高速）
        workingImage.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);

        double tol = tolerance; // ループ内で使う値を呼び出し時点の値に固定
        int replacementArgb = toArgbInt(replacement);

        editingControls.forEach(n -> n.setDisable(true));

        Task<int[]> task = new Task<>() {
            @Override
            protected int[] call() {
                for (int i = 0; i < pixels.length; i++) {
                    Color c = fromArgbInt(pixels[i]);
                    if (colorDiffLab(c, target) <= tol) {
                        pixels[i] = replacementArgb;
                    }
                }
                return pixels;
            }
        };

        task.setOnSucceeded(ev -> {
            // 書き込みもFXスレッド上でバルク実行
            workingImage.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), task.getValue(), 0, w);
            imageView.setImage(workingImage);
            editingControls.forEach(n -> n.setDisable(false));
            if (onComplete != null) onComplete.run();
        });

        task.setOnFailed(ev -> {
            editingControls.forEach(n -> n.setDisable(false));
            showError("色の置き換え中にエラーが発生しました。");
        });

        Thread worker = new Thread(task, "color-replace-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /** 元画像の状態にバルクコピーで高速に戻す（ピクセル毎のColorオブジェクト生成を避ける）。 */
    private void resetToOriginalColors(ImageView imageView) {
        if (originalImage == null || workingImage == null) return;
        int w = (int) originalImage.getWidth();
        int h = (int) originalImage.getHeight();
        int[] buffer = new int[w * h];
        originalImage.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), buffer, 0, w);
        workingImage.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), buffer, 0, w);
        imageView.setImage(workingImage);
    }

    private int toArgbInt(Color c) {
        int a = (int) Math.round(c.getOpacity() * 255);
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private Color fromArgbInt(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return Color.rgb(r, g, b, a / 255.0);
    }

    private WritableImage deepCopy(WritableImage src) {
        return new WritableImage(src.getPixelReader(), (int) src.getWidth(), (int) src.getHeight());
    }

    private void pushUndoState(Button undoButton) {
        if (workingImage == null) return;
        undoStack.push(deepCopy(workingImage));
        // Undo履歴の上限
        while (undoStack.size() > MAX_UNDO_HISTORY) {
            undoStack.removeLast();
        }
        undoButton.setDisable(false);
    }

    /** 指定スロットのサムネイル・ツールチップ・見た目（クリック可否）を更新する。 */
    private void updateVersionSlotVisual(int index, VersionSlotUI[] versionSlotUIs) {
        VersionSlotUI ui = versionSlotUIs[index];
        WritableImage img = versionSlots[index];
        ui.thumbnail.setImage(img);
        if (img == null) {
            ui.frame.setCursor(Cursor.DEFAULT);
            ui.frame.setOpacity(0.5);
            ui.tooltip.setText("未保存");
        } else {
            ui.frame.setCursor(Cursor.HAND);
            ui.frame.setOpacity(1.0);
            ui.tooltip.setText("保存日時: " + versionSlotTimes[index] + "\nクリックして呼び出す");
        }
    }

    /** 新しい画像/パターンを読み込んだ際に、以前の保存スロットとサムネイル表示をクリアする。 */
    private void resetVersionSlots(VersionSlotUI[] versionSlotUIs) {
        for (int i = 0; i < versionSlots.length; i++) {
            versionSlots[i] = null;
            versionSlotTimes[i] = null;
            updateVersionSlotVisual(i, versionSlotUIs);
        }
        nextVersionSlot = 0;
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}