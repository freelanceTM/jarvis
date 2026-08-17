package com.jarvis.assistant.agent.memory.semantic

/**
 * Слой НАСТОЯЩИХ embeddings — контракт на будущее.
 *
 *   EmbeddingProvider
 *    ├── LocalEmbeddingProvider   (ONNX / TFLite-модель в APK)
 *    └── RemoteEmbeddingProvider  (удалённый embeddings-эндпоинт)
 *
 * В v0.2 в проекте НЕТ ни одной embedding-модели, и мы её не добавляем:
 * тянуть в APK многомегабайтную модель ради галочки не входит в задачи
 * текущего milestone. Поэтому оба провайдера существуют как честные
 * заглушки-контракты: [isReady] возвращает false с объяснением причины,
 * [embed] — null. Возвращать «примерный» вектор при неготовой модели
 * запрещено: это незаметно исказило бы поиск по памяти.
 *
 * Текущий рабочий путь — [SemanticTextMatcher], который ЧЕСТНО называется
 * лексико-семантическим матчером (ручные векторы: словарь корней, синонимы,
 * хеши, n-граммы), а не ML-эмбеддингом.
 */
interface EmbeddingProvider {
    /** Человекочитаемое имя провайдера/модели — попадает в логи и настройки. */
    val modelId: String

    /** Размерность выходного вектора. */
    val dimensions: Int

    /** Готова ли модель к работе (загружена, файлы на месте, есть сеть и т. п.). */
    suspend fun isReady(): Boolean

    /**
     * @return вектор длины [dimensions] или null, если модель недоступна.
     */
    suspend fun embed(text: String): FloatArray?
}

/**
 * Локальная embedding-модель (ONNX / TFLite в APK).
 *
 * Честное состояние v0.2: модель не загружена в проект — провайдер сообщает
 * об этом и НЕ выдаёт векторы. Когда модель появится, реализация заменит
 * заглушку без изменения контракта.
 */
class LocalEmbeddingProvider : EmbeddingProvider {

    override val modelId: String = "local.onnx.placeholder"
    override val dimensions: Int = 384

    override suspend fun isReady(): Boolean = false

    override suspend fun embed(text: String): FloatArray? = null

    /** Причина недоступности — для честного сообщения в UI/логах. */
    val unavailabilityReason: String = "Локальная embedding-модель не включена в APK (v0.2)"
}

/**
 * Удалённый embeddings-эндпоинт.
 *
 * Честное состояние v0.2: эндпоинт не настроен — провайдер сообщает об этом
 * и НЕ выдаёт векторы. Когда появится серверная часть, реализация заменит
 * заглушку без изменения контракта.
 */
class RemoteEmbeddingProvider : EmbeddingProvider {

    override val modelId: String = "remote.embedding.endpoint"
    override val dimensions: Int = 1536

    override suspend fun isReady(): Boolean = false

    override suspend fun embed(text: String): FloatArray? = null

    /** Причина недоступности — для честного сообщения в UI/логах. */
    val unavailabilityReason: String = "Удалённый embedding-эндпоинт не настроен (v0.2)"
}
