package dijkstra.visualizer.dijkstravisualizer;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public class DijkstraVisualizer extends Application {

  public static void main(String[] args) { launch(args); }

  @Override
  public void start(Stage stage) {
    var ui = new UI();
    var state = new VizState();
    var graph = new Graph();
    var viewport = new Viewport();
    var steps = new ConcurrentLinkedQueue<Step>();
    var renderer = new GraphRenderer(graph, state, viewport, ui.canvas, ui.showWeights);
    var animator = new StepAnimator(ui.speed, steps, s -> {
      state.apply(s, graph, ui.status, state.target);
      if (s.type == StepType.DONE) ui.onAlgoDone();
    }, renderer::draw);

    ui.bindActions(
        () -> {
          var n = parseInt(ui.tfNodes.getText(), 1000);
          var p = parseDouble(ui.tfProb.getText(), 0.004);
          GraphGenerator.generate(graph, n, p);
          state.reset();
          ui.afterGenerate(n, p);
          renderer.draw();
        },
        () -> {
          if (state.source < 0) { ui.status.setText("Pick a source and a target"); return; }
          animator.stop();
          state.currentRunner = new DijkstraRunner(graph, state.source, state.target < 0 ? state.source : state.target, steps::add);
          state.algoThread = new Thread(state.currentRunner, "dijkstra-thread");
          state.algoThread.setDaemon(true);
          state.algoThread.start();
          animator.start();
          ui.afterStart();
        },
        () -> { animator.stop(); ui.afterPause(); },
        () -> {
          state.stopAlgo();
          state.reset();
          renderer.draw();
          ui.afterReset();
        }
    );

    Interactions.attach(ui.canvas, viewport, (x, y) -> {
      int hit = Picks.nodeAt(graph, viewport, x, y);
      if (hit >= 0) {
        if (state.source < 0) { state.source = hit; ui.status.setText("Source: " + hit + ". Pick target"); }
        else if (state.target < 0 || hit == state.source) { state.target = hit; ui.status.setText("Target: " + hit + ". Start"); ui.btnStart.setDisable(false); }
        else { state.source = hit; state.target = -1; ui.status.setText("Source: " + hit + ". Pick target"); }
        renderer.draw();
      }
    }, renderer::draw);

    ui.btnGenerate.fire();

    var root = new BorderPane();
    root.setTop(ui.topBar());
    root.setCenter(new StackPane(ui.canvas));
    var spacer = new Region();
    var bottom = new HBox(10, new Label("Speed:"), ui.speed, spacer, ui.status);
    HBox.setHgrow(spacer, Priority.ALWAYS);
    BorderPane.setMargin(bottom, new Insets(6));
    BorderPane.setMargin(ui.topBar(), new Insets(6));
    root.setBottom(bottom);

    stage.setTitle("Dijkstra Visualizer — JavaFX");
    stage.setScene(new Scene(root));
    stage.show();
    renderer.draw();
  }

  static final class UI {
    final Canvas canvas = new Canvas(1200, 800);
    final Label status = new Label("Ready");
    final Slider speed = new Slider(1, 120, 30);
    final TextField tfNodes = new TextField("2000");
    final TextField tfProb = new TextField("0.004");
    final Button btnGenerate = new Button("Generate");
    final Button btnStart = new Button("Start");
    final Button btnPause = new Button("Pause");
    final Button btnReset = new Button("Reset");
    final CheckBox showWeights = new CheckBox("Show weights");
    private Pane top;

    Pane topBar() {
      if (top != null) return top;
      tfNodes.setPrefWidth(80);
      tfProb.setPrefWidth(80);
      speed.setPrefWidth(200);
      speed.setShowTickLabels(true);
      showWeights.setSelected(false);
      btnStart.setDisable(true);
      btnPause.setDisable(true);
      btnReset.setDisable(true);
      var srcLbl = new Label("Click nodes: source→target");
      top = new HBox(10, new Label("Nodes"), tfNodes, new Label("Edge prob"), tfProb,
          btnGenerate, new Separator(), btnStart, btnPause, btnReset, new Separator(), showWeights, new Separator(), srcLbl);
      ((HBox) top).setAlignment(javafx.geometry.Pos.CENTER_LEFT);
      ((HBox) top).setPadding(new Insets(4));
      return top;
    }

    void bindActions(Runnable onGen, Runnable onStart, Runnable onPause, Runnable onReset) {
      btnGenerate.setOnAction(e -> onGen.run());
      btnStart.setOnAction(e -> onStart.run());
      btnPause.setOnAction(e -> onPause.run());
      btnReset.setOnAction(e -> onReset.run());
    }

    void afterGenerate(int n, double p) {
      status.setText("Generated: " + n + " nodes, p=" + p);
      btnStart.setDisable(true);
      btnPause.setDisable(true);
      btnReset.setDisable(false);
    }

    void afterStart() {
      btnStart.setDisable(true);
      btnPause.setDisable(false);
      btnReset.setDisable(false);
    }

    void afterPause() {
      btnStart.setDisable(false);
      btnPause.setDisable(true);
      status.setText("Paused");
    }

    void onAlgoDone() {
      btnPause.setDisable(true);
      btnStart.setDisable(false);
    }

    void afterReset() {
      btnStart.setDisable(true);
      btnPause.setDisable(true);
      btnReset.setDisable(false);
      status.setText("Ready");
    }
  }

  static final class Viewport {
    final DoubleProperty scale = new SimpleDoubleProperty(1.0);
    final DoubleProperty offsetX = new SimpleDoubleProperty(0);
    final DoubleProperty offsetY = new SimpleDoubleProperty(0);

    Point2D toWorld(double sx, double sy) {
      return new Point2D((sx - offsetX.get()) / scale.get(), (sy - offsetY.get()) / scale.get());
    }

    void zoomAt(double sx, double sy, double factor) {
      scale.set(clamp(scale.get() * factor, 0.05, 10));
      var after = toWorld(sx, sy);
      offsetX.set(offsetX.get() + (sx - (after.getX() * scale.get() + offsetX.get())));
      offsetY.set(offsetY.get() + (sy - (after.getY() * scale.get() + offsetY.get())));
    }
  }

  static final class Interactions {
    static void attach(Canvas c, Viewport v, BiClick onPick, Runnable onRedraw) {
      var last = new Object() { Point2D p; };
      c.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
        if (e.getButton() == MouseButton.MIDDLE || (e.getButton() == MouseButton.PRIMARY && e.isShortcutDown())) {
          last.p = new Point2D(e.getX(), e.getY());
        } else if (e.getButton() == MouseButton.PRIMARY) {
          onPick.hit(e.getX(), e.getY());
        }
      });
      c.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
        if (last.p != null) {
          var dx = e.getX() - last.p.getX();
          var dy = e.getY() - last.p.getY();
          v.offsetX.set(v.offsetX.get() + dx);
          v.offsetY.set(v.offsetY.get() + dy);
          last.p = new Point2D(e.getX(), e.getY());
          onRedraw.run();
        }
      });
      c.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> last.p = null);
      c.addEventHandler(ScrollEvent.SCROLL, e -> {
        var factor = Math.pow(1.0015, e.getDeltaY());
        v.zoomAt(e.getX(), e.getY(), factor);
        onRedraw.run();
      });
    }

    interface BiClick { void hit(double x, double y); }
  }

  static final class Picks {
    static int nodeAt(Graph g, Viewport v, double sx, double sy) {
      var w = v.toWorld(sx, sy);
      var r = 6 / v.scale.get();
      int best = -1;
      double bd = Double.MAX_VALUE;
      for (var n : g.nodes) {
        var dx = n.x - w.getX();
        var dy = n.y - w.getY();
        var d = Math.hypot(dx, dy);
        if (d < r && d < bd) { bd = d; best = n.id; }
      }
      return best;
    }
  }

  static final class VizState {
    int source = -1, target = -1;
    final Set<Integer> settled = new HashSet<>();
    final Map<Integer, Double> dist = new HashMap<>();
    final Map<Integer, Integer> prev = new HashMap<>();
    final Set<Long> relaxedOk = new HashSet<>();
    final Set<Long> relaxedSkip = new HashSet<>();
    DijkstraRunner currentRunner;
    Thread algoThread;

    void reset() {
      settled.clear();
      dist.clear();
      prev.clear();
      relaxedOk.clear();
      relaxedSkip.clear();
      source = -1;
      target = -1;
    }

    void stopAlgo() {
      if (currentRunner != null) currentRunner.stop = true;
      if (algoThread != null) {
        algoThread.interrupt();
        try { algoThread.join(50); } catch (InterruptedException ignored) {}
        algoThread = null;
      }
      currentRunner = null;
    }

    void apply(Step s, Graph g, Label status, int tgt) {
      switch (s.type) {
        case START -> status.setText("Running: source=" + s.from + (tgt >= 0 ? (" target=" + tgt) : ""));
        case SETTLE -> settled.add(s.from);
        case RELAX_OK -> {
          dist.put(s.to, s.newDist);
          prev.put(s.to, s.from);
          relaxedOk.add((((long) s.from) << 32) | (s.to & 0xffffffffL));
        }
        case RELAX_SKIP -> relaxedSkip.add((((long) s.from) << 32) | (s.to & 0xffffffffL));
        case DONE -> status.setText("Done. " + (tgt >= 0 && dist.containsKey(tgt) ? ("dist[" + tgt + "]=" + fmt(dist.get(tgt))) : ""));
      }
    }
  }

  static final class GraphRenderer {
    final Graph graph;
    final VizState state;
    final Viewport viewport;
    final Canvas canvas;
    final CheckBox showWeights;

    GraphRenderer(Graph graph, VizState state, Viewport viewport, Canvas canvas, CheckBox showWeights) {
      this.graph = graph;
      this.state = state;
      this.viewport = viewport;
      this.canvas = canvas;
      this.showWeights = showWeights;
    }

    void draw() {
      var g = canvas.getGraphicsContext2D();
      g.setFill(Color.web("#0b1021"));
      g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
      g.save();
      g.translate(viewport.offsetX.get(), viewport.offsetY.get());
      g.scale(viewport.scale.get(), viewport.scale.get());
      drawEdges(g);
      drawPath(g);
      drawNodes(g);
      g.restore();
      overlay(g);
    }

    void drawEdges(GraphicsContext g) {
      g.setLineWidth(1 / viewport.scale.get());
      for (int u = 0; u < graph.size(); u++) {
        for (var e : graph.adj.get(u)) {
          if (e.u > e.v) continue;
          var a = graph.nodes.get(e.u);
          var b = graph.nodes.get(e.v);
          var uv = ((long) e.u << 32) | (e.v & 0xffffffffL);
          var vu = ((long) e.v << 32) | (e.u & 0xffffffffL);
          var color = Color.gray(0.5);
          if (state.relaxedOk.contains(uv) || state.relaxedOk.contains(vu)) color = Color.web("#7dd3fc");
          else if (state.relaxedSkip.contains(uv) || state.relaxedSkip.contains(vu)) color = Color.gray(0.3);
          if (state.settled.contains(e.u) && state.settled.contains(e.v)) color = Color.web("#94a3b8");
          g.setStroke(color);
          g.strokeLine(a.x, a.y, b.x, b.y);
          if (showWeights.isSelected()) {
            g.setFill(Color.gray(0.7));
            g.setFont(Font.font(10 / viewport.scale.get()));
            var mx = (a.x + b.x) / 2;
            var my = (a.y + b.y) / 2;
            g.fillText(String.format(Locale.US, "%.0f", e.w), mx, my);
          }
        }
      }
    }

    void drawPath(GraphicsContext g) {
      if (state.target < 0 || !state.prev.containsKey(state.target)) return;
      g.setStroke(Color.web("#fbbf24"));
      g.setLineWidth(3 / viewport.scale.get());
      var cur = state.target;
      var guard = graph.size() + 5;
      while (cur != state.source && guard-- > 0 && state.prev.containsKey(cur)) {
        int p = state.prev.get(cur);
        var a = graph.nodes.get(p);
        var b = graph.nodes.get(cur);
        g.strokeLine(a.x, a.y, b.x, b.y);
        cur = p;
      }
    }

    void drawNodes(GraphicsContext g) {
      var r = 3 / viewport.scale.get();
      for (var n : graph.nodes) {
        Color fill = Color.web("#e2e8f0");
        if (n.id == state.source) fill = Color.web("#22c55e");
        else if (n.id == state.target) fill = Color.web("#ef4444");
        else if (state.settled.contains(n.id)) fill = Color.web("#a5b4fc");
        g.setFill(fill);
        g.fillOval(n.x - r, n.y - r, 2 * r, 2 * r);
      }
    }

    void overlay(GraphicsContext g) {
      g.setFill(Color.WHITE);
      g.setFont(Font.font(14));
      g.fillText(
          "Nodes: " + graph.size()
              + "  scale=" + String.format(Locale.US, "%.2f", viewport.scale.get())
              + "  settled=" + state.settled.size()
              + (state.target >= 0 && state.dist.containsKey(state.target) ? ("  dist[target]=" + fmt(state.dist.get(state.target))) : ""),
          10, 18
      );
    }
  }

  static final class StepAnimator {
    private AnimationTimer timer;
    private long lastNanos;
    private double accumulator;
    private final Slider speed;
    private final Queue<Step> steps;
    private final Consumer<Step> apply;
    private final Runnable redraw;

    StepAnimator(Slider speed, Queue<Step> steps, Consumer<Step> apply, Runnable redraw) {
      this.speed = speed;
      this.steps = steps;
      this.apply = apply;
      this.redraw = redraw;
      timer = new AnimationTimer() {
        @Override public void handle(long now) {
          if (lastNanos == 0) { lastNanos = now; return; }
          var dt = (now - lastNanos) / 1e9;
          lastNanos = now;
          accumulator += dt;
          var interval = 1.0 / speed.getValue();
          var changed = false;
          while (accumulator >= interval) {
            var s = steps.poll();
            if (s == null) break;
            apply.accept(s);
            changed = true;
            accumulator -= interval;
            if (s.type == StepType.DONE) break;
          }
          if (changed) redraw.run();
        }
      };
    }

    void start() { lastNanos = 0; accumulator = 0; timer.start(); }
    void stop() { timer.stop(); }
  }

  static final class GraphGenerator {
    static void generate(Graph g, int n, double p) {
      g.clear();
      var rnd = new Random(42);
      double W = 5000, H = 5000;
      for (int i = 0; i < n; i++) g.addNode(new Node(i, rnd.nextDouble() * W, rnd.nextDouble() * H));
      for (int u = 0; u < n; u++) {
        for (int v = u + 1; v < n; v++) {
          if (rnd.nextDouble() < p) {
            var a = g.nodes.get(u);
            var b = g.nodes.get(v);
            var d = Math.hypot(a.x - b.x, a.y - b.y);
            var w = d * (0.9 + 0.2 * rnd.nextDouble());
            g.addUndirected(u, v, w);
          }
        }
      }
      var k = Math.max(2, (int) Math.round(Math.log(n)));
      for (int u = 0; u < n; u++) {
        var list = new ArrayList<int[]>();
        var a = g.nodes.get(u);
        for (int v = 0; v < n; v++) if (u != v) {
          var b = g.nodes.get(v);
          var d = Math.hypot(a.x - b.x, a.y - b.y);
          list.add(new int[]{v, (int) (d * 1000)});
        }
        list.sort(Comparator.comparingInt(ar -> ar[1]));
        for (int i = 0; i < k; i++) {
          int v = list.get(i)[0];
          var b = g.nodes.get(v);
          var d = Math.hypot(a.x - b.x, a.y - b.y);
          g.addUndirected(u, v, d);
        }
      }
    }
  }

  static final class Graph {
    final List<Node> nodes = new ArrayList<>();
    final List<List<Edge>> adj = new ArrayList<>();
    int size() { return nodes.size(); }
    void addNode(Node n) { nodes.add(n); adj.add(new ArrayList<>()); }
    void addUndirected(int u, int v, double w) {
      if (u == v) return;
      adj.get(u).add(new Edge(u, v, w));
      adj.get(v).add(new Edge(v, u, w));
    }
    void clear() { nodes.clear(); adj.clear(); }
  }

  static final class Node { final int id; final double x, y; Node(int id, double x, double y) { this.id = id; this.x = x; this.y = y; } }
  static final class Edge { final int u, v; final double w; Edge(int u, int v, double w) { this.u = u; this.v = v; this.w = w; } }

  enum StepType { SETTLE, RELAX_OK, RELAX_SKIP, START, DONE }

  static final class Step {
    final StepType type; final int from; final int to; final double newDist;
    Step(StepType type, int from, int to, double newDist) { this.type = type; this.from = from; this.to = to; this.newDist = newDist; }
    static Step start(int src) { return new Step(StepType.START, src, -1, 0); }
    static Step settle(int u) { return new Step(StepType.SETTLE, u, -1, 0); }
    static Step relaxOk(int u, int v, double nd) { return new Step(StepType.RELAX_OK, u, v, nd); }
    static Step relaxSkip(int u, int v) { return new Step(StepType.RELAX_SKIP, u, v, Double.NaN); }
    static Step done() { return new Step(StepType.DONE, -1, -1, 0); }
  }

  static final class DijkstraRunner implements Runnable {
    final Graph g; final int src; final int target; final Consumer<Step> out;
    volatile boolean stop = false;
    DijkstraRunner(Graph g, int src, int target, Consumer<Step> out) { this.g = g; this.src = src; this.target = target; this.out = out; }
    @Override public void run() {
      int n = g.size();
      var dist = new double[n];
      var prev = new int[n];
      Arrays.fill(dist, Double.POSITIVE_INFINITY);
      Arrays.fill(prev, -1);
      dist[src] = 0;
      out.accept(Step.start(src));
      var pq = new PriorityQueue<int[]>(Comparator.comparingDouble(a -> dist[a[0]]));
      pq.add(new int[]{src});
      var settled = new boolean[n];
      while (!pq.isEmpty() && !stop) {
        int u = pq.poll()[0];
        if (settled[u]) continue;
        settled[u] = true;
        out.accept(Step.settle(u));
        if (u == target) break;
        for (var e : g.adj.get(u)) {
          if (settled[e.v]) { out.accept(Step.relaxSkip(u, e.v)); continue; }
          var nd = dist[u] + e.w;
          if (nd < dist[e.v]) {
            dist[e.v] = nd; prev[e.v] = u;
            out.accept(Step.relaxOk(u, e.v, nd));
            pq.add(new int[]{e.v});
          } else {
            out.accept(Step.relaxSkip(u, e.v));
          }
        }
      }
      out.accept(Step.done());
    }
  }

  static String fmt(double v) { return String.format(Locale.US, "%.1f", v); }
  static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
  static double parseDouble(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }
  static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
}
