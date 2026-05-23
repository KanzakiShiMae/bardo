package com.musicplayer;

/**
 * Punto de entrada para el JAR ejecutable.
 *
 * No extiende {@code Application}, lo que evita el error
 * "JavaFX runtime components are missing" al arrancar desde
 * un fat JAR donde los módulos JavaFX van en el classpath.
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
