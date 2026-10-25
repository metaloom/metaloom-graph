package io.metaloom.graph.apilock.fixture;

/** A sealed interface over two records. */
public sealed interface Shape permits Shape.Circle, Shape.Square {

	double area();

	record Circle(double radius) implements Shape {

		@Override
		public double area() {
			return Math.PI * radius * radius;
		}
	}

	record Square(double side, java.util.List<String> tags) implements Shape {

		public Square(double side) {
			this(side, java.util.List.of());
		}

		@Override
		public double area() {
			return side * side;
		}
	}
}
