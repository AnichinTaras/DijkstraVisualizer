module dijkstra.visualizer.dijkstravisualizer {
  requires javafx.controls;
  requires javafx.fxml;


  opens dijkstra.visualizer.dijkstravisualizer to javafx.fxml;
  exports dijkstra.visualizer.dijkstravisualizer;
}