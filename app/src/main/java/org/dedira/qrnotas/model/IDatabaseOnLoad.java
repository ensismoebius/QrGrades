package org.dedira.qrnotas.model;

public interface IDatabaseOnLoad<T> {
    void onLoadComplete(boolean success, T object);
}
