package com.jarvis.assistant.agent.memory.semantic

/**
 * Слой расширения для НАСТОЯЩИХ embeddings.
 *
 *   EmbeddingEngine → EmbeddingModel → VectorStore
 *
 * В v0.2 в проекте нет ML-модели, и мы её не добавляем: тянуть в APK
 * многомегабайтную модель ради галочки не входит в задачи текущего milestone.
 * Но контракт зафиксирован, чтобы подключение модели (ONNX / TFLite / удалённый
 * embeddings-эндпоинт) не требовало переписывать память и Tool Discovery.
 *
 * Текущий рабочий путь — [SemanticFeatureEngine], который честно называется
 * лексико-семантическим признаковым движком, а не ML-эмбеддингом.
 */
interface EmbeddingModel {
    /** Человекочитаемое имя модели, попадает в логи и настройки. */
    val modelId: String

    /** Размерность выходного вектора. */
    val dimensions: Int

    /** Готова ли модель к работе (загружена, файлы на месте, есть сеть и т. п.). */
    suspend fun isReady(): Boolean

    /**
     * @return вектор длины [dimensions] или null, если модель недоступна.
     *         Возвращать «примерный» вектор при неготовой модели запрещено —
     *         это исказит поиск по памяти незаметно для пользователя.
     */
    suspend fun embed(text: String): FloatArray?
}

/**
 * Хранилище векторов. Текущая реализация памяти держит векторы в Room
 * (сериализованная строка в MemoryEntity.embeddingVector); интерфейс выделен,
 * чтобы позже заменить на настоящий ANN-индекс без изменения вызывающего кода.
 */
interface VectorStore {
    suspend fun upsert(id: String, vector: FloatArray, payload: String)
    suspend fun search(query: FloatArray, limit: Int): List<VectorMatch>
    suspend fun delete(id: String)
}

data class VectorMatch(
    val id: String,
    val score: Float,
    val payload: String
)
