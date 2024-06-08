package org.dedira.qrnotas.model;

public interface IDatabaseOnSave<T> {
    void onSaveComplete(boolean success, T object);
}
