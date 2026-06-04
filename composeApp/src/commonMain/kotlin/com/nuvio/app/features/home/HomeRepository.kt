package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.findCollectionCatalog
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.trailer.HeroTrailerSourceCache
import com.nuvio.app.features.trailer.TrailerPreBufferService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.random.Random

object HomeRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    // Resolved trailer playback sources keyed by "type:id". Populated by the
    // background pre-warm — resolution happens in the stable Repository scope so
    // it is never cancelled by a Compose composition scope leaving composition.
    private val _trailerSources = MutableStateFlow<Map<String, TrailerPlaybackSource>>(emptyMap())
    val trailerSources: StateFlow<Map<String, TrailerPlaybackSource>> = _trailerSources.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequestKey: String? = null
    private var lastRequestKey: String? = null
    private var currentDefinitions: List<HomeCatalogDefinition> = emptyList()
    private var cachedSections: Map<String, HomeCatalogSection> = emptyMap()
    private var cachedCollectionHeroItems: List<MetaPreview> = emptyList()
    private var collectionHeroJob: Job? = null
    private var collectionHeroRequestKey: String? = null
    private var heroTrailerEnrichmentJob: Job? = null
    // Pre-warm job is tracked separately from heroTrailerEnrichmentJob because it is
    // launched on the stable repository scope and is NOT automatically cancelled when
    // heroTrailerEnrichmentJob is cancelled. Without explicit tracking, a profile switch
    // that calls clear() would cancel enrichment but leave the old pre-warm running,
    // which then writes the previous profile's trailer sources back into _trailerSources.
    private var heroTrailerPreWarmJob: Job? = null
    // Slide 0 pre-warm is kicked off the moment its TMDB/Stremio keys are resolved,
    // without waiting for the other 7 slides to finish enrichment. Tracked separately
    // so clear() and publishCurrentState can cancel it on profile switch / hero reset.
    private var slide0EarlyPreWarmJob: Job? = null
    // The hero-item stableKeys ("type:id") that the most recently STARTED enrichment
    // run was launched for. publishCurrentState compares against this before deciding
    // whether to cancel + restart enrichment. If the hero items haven't changed (same
    // set of titles, same order), we let the in-flight job finish rather than killing
    // it and paying the full YouTube-extraction cost again. This is the main reason
    // trailers were taking 6-8 s: enrichment was being restarted 6-8 times during
    // catalog loading because publishCurrentState fires once per batch.
    private var lastEnrichedHeroKeys: List<String> = emptyList()
    // Caches resolved trailer keys per "type:id" so re-emissions of the same hero
    // list don't re-launch lookups (which were getting cancelled mid-flight before
    // this). Stores empty lists too — a negative cache so we don't keep retrying
    // titles with no trailer. First entry is the preferred key (TMDB if available),
    // the rest are alternates (from Stremio) tried in order if the primary fails to
    // extract a playable URL.
    private val trailerKeyCache: MutableMap<String, List<String>> = mutableMapOf()
    // Sticky pick of hero items. Once chosen, we keep these across catalog refreshes so
    // a background sync doesn't yank the slide the user is currently watching. Only
    // re-picked when these items are no longer available in the underlying catalogs.
    private var cachedHeroItems: List<MetaPreview> = emptyList()
    private var lastPublishedCatalogHeroEmpty: Boolean = true
    private var lastErrorMessage: String? = null

    fun refresh(addons: List<ManagedAddon>, force: Boolean = false) {
        val activeAddons = addons.enabledAddons()
        val requests = buildHomeCatalogDefinitions(activeAddons)
        currentDefinitions = requests
        val requestKeys = requests.mapTo(mutableSetOf(), HomeCatalogDefinition::key)
        cachedSections = cachedSections.filterKeys(requestKeys::contains)
        val requestKey = requests.joinToString(separator = "|") { request ->
            "${request.manifestUrl}:${request.type}:${request.catalogId}"
        }

        if (!force && activeRequestKey == requestKey && _uiState.value.isLoading) return

        if (!force && requestKey == lastRequestKey && requestKeys.all(cachedSections::containsKey)) {
            if (_uiState.value.sections.isEmpty() || _uiState.value.heroItems.isEmpty()) {
                applyCurrentSettings()
            }
            return
        }
        lastRequestKey = requestKey
        activeRequestKey = requestKey
        // On a forced refresh the catalog is reloaded from scratch and may produce
        // entirely different hero items — reset the enrichment guard so the new items
        // get a fresh enrichment run rather than being skipped as "already done".
        if (force) lastEnrichedHeroKeys = emptyList()

        if (requests.isEmpty()) {
            activeJob?.cancel()
            activeJob = null
            activeRequestKey = null
            cachedSections = emptyMap()
            lastErrorMessage = null
            publishCurrentState(
                isLoading = false,
                requestKey = requestKey,
            )
            ensureCollectionHeroFallback(
                addons = activeAddons,
                force = force,
                requestKey = requestKey,
            )
            return
        }

        activeJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        activeJob = scope.launch {
            val prioritizedRequests = prioritizeDefinitions(
                definitions = requests,
                snapshot = HomeCatalogSettingsRepository.snapshot(),
            )
            val pendingRequests = prioritizedRequests.filter { definition ->
                force || cachedSections[definition.key] == null
            }
            if (pendingRequests.isEmpty()) {
                publishCurrentState(
                    isLoading = false,
                    requestKey = requestKey,
                )
                return@launch
            }
            val loadedSections = linkedMapOf<String, HomeCatalogSection>().apply {
                putAll(cachedSections)
            }
            var firstErrorMessage: String? = null
            var batchIndex = 0

            pendingRequests.chunked(HOME_CATALOG_FETCH_BATCH_SIZE).forEach { batch ->
                if (activeRequestKey != requestKey) return@launch
                val results = batch.map { request ->
                    async { runCatching { request.toSection() } }
                }.awaitAll()

                if (activeRequestKey != requestKey) return@launch

                results.mapNotNull { it.getOrNull() }.forEach { section ->
                    loadedSections[section.key] = section
                }
                if (firstErrorMessage == null) {
                    firstErrorMessage = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                }
                cachedSections = loadedSections.toMap()
                lastErrorMessage = firstErrorMessage
                if (batchIndex == 0 || (batchIndex + 1) % HOME_CATALOG_PUBLISH_INTERVAL == 0) {
                    publishCurrentState(
                        isLoading = true,
                        requestKey = requestKey,
                    )
                }
                batchIndex++
            }

            if (activeRequestKey != requestKey) return@launch

            cachedSections = loadedSections.toMap()
            lastErrorMessage = firstErrorMessage
            publishCurrentState(
                isLoading = false,
                requestKey = requestKey,
            )
            ensureCollectionHeroFallback(
                addons = activeAddons,
                force = force,
                requestKey = requestKey,
            )
        }
    }

    fun applyCurrentSettings() {
        publishCurrentState(
            isLoading = _uiState.value.isLoading,
            requestKey = activeRequestKey ?: lastRequestKey,
        )
        ensureCollectionHeroFallback(
            addons = AddonRepository.uiState.value.addons.enabledAddons(),
            force = false,
            requestKey = activeRequestKey ?: lastRequestKey,
        )
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        activeRequestKey = null
        lastRequestKey = null
        currentDefinitions = emptyList()
        cachedSections = emptyMap()
        cachedCollectionHeroItems = emptyList()
        collectionHeroJob?.cancel()
        collectionHeroJob = null
        collectionHeroRequestKey = null
        lastPublishedCatalogHeroEmpty = true
        lastErrorMessage = null
        lastEnrichedHeroKeys = emptyList()
        heroTrailerEnrichmentJob?.cancel()
        heroTrailerEnrichmentJob = null
        slide0EarlyPreWarmJob?.cancel()
        slide0EarlyPreWarmJob = null
        heroTrailerPreWarmJob?.cancel()
        heroTrailerPreWarmJob = null
        _uiState.value = HomeUiState()
        _trailerSources.value = emptyMap()
    }

    private fun publishCurrentState(
        isLoading: Boolean,
        requestKey: String?,
    ) {
        val snapshot = HomeCatalogSettingsRepository.snapshot()
        val preferences = snapshot.preferences
        val todayIsoDate = if (snapshot.hideUnreleasedContent) CurrentDateProvider.todayIsoDate() else null
        fun HomeCatalogSection.withReleaseFilter(): HomeCatalogSection =
            if (todayIsoDate == null) this else filterReleasedItems(todayIsoDate)

        val sections = currentDefinitions
            .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .mapNotNull { definition ->
                val preference = preferences[definition.key]
                if (preference?.enabled == false) return@mapNotNull null

                val section = cachedSections[definition.key]?.withReleaseFilter() ?: return@mapNotNull null
                if (section.items.isEmpty()) return@mapNotNull null
                val customTitle = preference?.customTitle.orEmpty()
                section.copy(
                    title = customTitle.ifBlank { section.title },
                )
            }

        val catalogHeroItems = if (snapshot.heroEnabled) {
            val availablePool = currentDefinitions
                .filter { definition -> preferences[definition.key]?.heroSourceEnabled != false }
                .mapNotNull { definition -> cachedSections[definition.key] }
                .map { section -> section.withReleaseFilter() }
                .flatMap { section -> section.items }
                .distinctBy { item -> "${item.type}:${item.id}" }

            // Reuse the existing pick if every cached hero item is still present in
            // the catalogs. This prevents the hero from re-shuffling mid-trailer when
            // a background catalog sync re-publishes the home state.
            val availableById = availablePool.associateBy { "${it.type}:${it.id}" }
            val cachedStillValid = cachedHeroItems.isNotEmpty() &&
                cachedHeroItems.all { "${it.type}:${it.id}" in availableById }

            if (cachedStillValid) {
                // Refresh the underlying MetaPreview from the latest catalog data
                // (so updated posters/metadata flow through) but keep the order and
                // any already-enriched trailer keys.
                cachedHeroItems.map { stale ->
                    val freshKey = "${stale.type}:${stale.id}"
                    val fresh = availableById[freshKey] ?: stale
                    if (stale.youtubeTrailerKey != null || stale.alternateTrailerKeys.isNotEmpty()) {
                        fresh.copy(
                            youtubeTrailerKey = stale.youtubeTrailerKey,
                            alternateTrailerKeys = stale.alternateTrailerKeys,
                        )
                    } else {
                        fresh
                    }
                }
            } else {
                val heroRandom = Random((requestKey?.hashCode() ?: 0).absoluteValue + 1)
                availablePool
                    .shuffled(heroRandom)
                    .take(HOME_HERO_ITEM_LIMIT)
                    .also { cachedHeroItems = it }
            }
        } else {
            cachedHeroItems = emptyList()
            emptyList()
        }
        lastPublishedCatalogHeroEmpty = snapshot.heroEnabled && catalogHeroItems.isEmpty()
        val heroItems = if (snapshot.heroEnabled) {
            catalogHeroItems.ifEmpty { cachedCollectionHeroItems }
        } else {
            emptyList()
        }

        _uiState.value = HomeUiState(
            isLoading = isLoading,
            heroItems = heroItems,
            sections = sections,
            errorMessage = if (sections.isEmpty()) lastErrorMessage else null,
        )

        // Enrich hero items with trailer keys in the background — does not block the UI update above.
        // Only restart if the set of hero items actually changed (different titles or different order).
        // publishCurrentState fires once per catalog batch (6-8 times during a full load), and the
        // old behaviour cancelled the in-flight extraction job every single time — costing a full
        // re-extraction cycle each restart. Guarding on key equality lets the first job run to
        // completion and shaves ~2s off first-trailer load time.
        val heroKeys = heroItems.map { it.stableKey() }
        if (heroKeys != lastEnrichedHeroKeys) {
            lastEnrichedHeroKeys = heroKeys
            heroTrailerEnrichmentJob?.cancel()
            slide0EarlyPreWarmJob?.cancel()
            slide0EarlyPreWarmJob = null
            if (heroItems.isNotEmpty()) {
                heroTrailerEnrichmentJob = scope.launch {
                    enrichHeroTrailers(heroItems)
                }
            }
        }
    }

    private suspend fun enrichHeroTrailers(items: List<MetaPreview>) {
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        if (!tmdbSettings.enabled) {
            println("🟡 (HeroTrailer) TMDB not enabled — skipping trailer enrichment")
            return
        }
        if (!tmdbSettings.hasApiKey) {
            println("🟡 (HeroTrailer) No TMDB API key — skipping trailer enrichment")
            return
        }
        val language = tmdbSettings.language
        println("🔵 (HeroTrailer) Enriching ${items.size} hero items for trailers (language=$language)")

        val enriched = coroutineScope {
            items.mapIndexed { index, item ->
                async {
                    val cacheKey = "${item.type}:${item.id}"

                    val enrichedItem = if (trailerKeyCache.containsKey(cacheKey)) {
                        // Cache hit — skip the network entirely.
                        val cached = trailerKeyCache[cacheKey].orEmpty()
                        item.copy(
                            youtubeTrailerKey = cached.firstOrNull(),
                            alternateTrailerKeys = cached.drop(1),
                        )
                    } else {
                        // Fetch TMDB and Stremio in parallel. We always want BOTH so the
                        // YouTube extractor has fallbacks when the primary key is dead
                        // (geo-blocked, age-gated, signature changed).
                        coroutineScope {
                            val tmdbDeferred = async {
                                runCatching {
                                    TmdbMetadataService.fetchHeroTrailerKey(
                                        id = item.id,
                                        type = item.type,
                                        language = language,
                                    )
                                }.getOrElse { e ->
                                    println("🟡 (HeroTrailer) TMDB fetch failed for ${item.id}: ${e.message}")
                                    null
                                }
                            }
                            val stremioDeferred = async {
                                runCatching {
                                    val meta = MetaDetailsRepository.fetch(type = item.type, id = item.id)
                                    val youtubeTrailers = meta?.trailers
                                        ?.filter { it.site.equals("YouTube", ignoreCase = true) }
                                        .orEmpty()
                                    // Officials first, then any others. Distinct so we don't
                                    // duplicate the same video.
                                    (youtubeTrailers.filter { it.official } + youtubeTrailers.filterNot { it.official })
                                        .map { it.key }
                                        .filter { it.isNotBlank() }
                                        .distinct()
                                }.getOrElse { e ->
                                    println("🟡 (HeroTrailer) Stremio fallback failed for ${item.id}: ${e.message}")
                                    emptyList<String>()
                                }
                            }

                            val tmdbKey = tmdbDeferred.await()
                            val stremioKeys = stremioDeferred.await()

                            // Build ordered key list: TMDB first (if present), then any
                            // Stremio keys that aren't dupes. The extractor will try them
                            // in order via HeroTrailerSourceCache.resolveFirstAvailable.
                            val combined = buildList {
                                if (!tmdbKey.isNullOrBlank()) add(tmdbKey)
                                stremioKeys.forEach { if (it != tmdbKey) add(it) }
                            }

                            if (currentCoroutineContext().isActive) {
                                trailerKeyCache[cacheKey] = combined
                            }

                            println("🔵 (HeroTrailer) ${item.name} → keys=${combined.size} (primary=${combined.firstOrNull() ?: "null"})")
                            item.copy(
                                youtubeTrailerKey = combined.firstOrNull(),
                                alternateTrailerKeys = combined.drop(1),
                            )
                        }
                    }

                    // Slide 0: kick off YouTube URL extraction immediately in the stable
                    // scope the moment its TMDB/Stremio keys are known — without waiting
                    // for the other 7 slides to finish enrichment. On a typical session
                    // this saves 0.5-2 s of first-trailer startup latency because the
                    // YouTube extractor for slide 0 starts while the other slides are still
                    // fetching metadata. Slides 1-7 are pre-warmed after awaitAll() below.
                    if (index == 0 && enrichedItem.youtubeTrailerKey != null) {
                        slide0EarlyPreWarmJob?.cancel()
                        slide0EarlyPreWarmJob = scope.launch { prewarmSingleSlide(enrichedItem, slideIndex = 0) }
                    }

                    enrichedItem
                }
            }.awaitAll()
        }

        val withTrailers = enriched.count { it.youtubeTrailerKey != null }
        println("🟢 (HeroTrailer) Enrichment complete — $withTrailers/${items.size} items have trailer keys")

        // Pre-warm slides 1-7 in the stable Repository scope (slide 0 was already
        // started above the moment its keys were resolved). Each item runs in parallel;
        // within each item keys are tried in order and we stop at the first working URL.
        heroTrailerPreWarmJob?.cancel()
        heroTrailerPreWarmJob = scope.launch {
            coroutineScope {
                enriched.drop(1).mapIndexed { i, item ->
                    async { prewarmSingleSlide(item, slideIndex = i + 1) }
                }.awaitAll()
            }
            println("🟢 (HeroTrailer) Pre-warm complete for all ${enriched.size} hero items")
        }

        // Only publish if the hero list hasn't changed while we were fetching
        _uiState.update { current ->
            val currentIds = current.heroItems.map { it.stableKey() }
            val enrichedIds = enriched.map { it.stableKey() }
            if (currentIds == enrichedIds) current.copy(heroItems = enriched) else current
        }

        // Sync the sticky cache with enriched keys so the next catalog re-publish
        // doesn't drop the trailer keys we just resolved.
        val cachedIds = cachedHeroItems.map { it.stableKey() }
        val enrichedIds = enriched.map { it.stableKey() }
        if (cachedIds == enrichedIds) {
            cachedHeroItems = enriched
        }
    }

    private suspend fun prewarmSingleSlide(item: MetaPreview, slideIndex: Int) {
        val stableKey = item.stableKey()
        val candidateKeys = buildList {
            val primary = item.youtubeTrailerKey
            if (!primary.isNullOrBlank()) add(primary)
            addAll(item.alternateTrailerKeys.take(4).filter { it.isNotBlank() })
        }
        if (candidateKeys.isEmpty()) return
        // Slide 0 gets a 360p muxed MP4 (single AVPlayer, fast startup).
        // All other slides use the quality adaptive split-stream path.
        val preferFastStart = slideIndex == 0
        val source = runCatching {
            HeroTrailerSourceCache.resolveFirstAvailable(candidateKeys, preferFastStart = preferFastStart)
        }.getOrNull()
        if (source != null) {
            _trailerSources.update { it + (stableKey to source) }
            println("🟢 (HeroTrailer) Pre-warm resolved ${item.name} (slide=$slideIndex fast=$preferFastStart) → ${source.videoUrl.take(60)}…")
            TrailerPreBufferService.prefetch(source.videoUrl, source.audioUrl)
        } else {
            println("🟡 (HeroTrailer) Pre-warm: no playable source for ${item.name}")
        }
        // Slide 0 was pre-warmed at 360p for fast home-screen startup. Also warm the
        // quality path so the detail screen gets full resolution without re-extracting.
        if (slideIndex == 0 && source != null) {
            runCatching {
                HeroTrailerSourceCache.resolveFirstAvailable(candidateKeys, preferFastStart = false)
            }
        }
    }

    private suspend fun HomeCatalogDefinition.toSection(): HomeCatalogSection {
        val page = fetchCatalogPage(
            manifestUrl = manifestUrl,
            type = type,
            catalogId = catalogId,
            maxItems = HOME_CATALOG_PREVIEW_FETCH_LIMIT,
        )
        val items = page.items
        if (items.isEmpty()) {
            return HomeCatalogSection(
                key = key,
                title = defaultTitle,
                subtitle = addonName,
                addonName = addonName,
                type = type,
                manifestUrl = manifestUrl,
                catalogId = catalogId,
                items = emptyList(),
                availableItemCount = 0,
                supportsPagination = supportsPagination,
            )
        }

        return HomeCatalogSection(
            key = key,
            title = defaultTitle,
            subtitle = addonName,
            addonName = addonName,
            type = type,
            manifestUrl = manifestUrl,
            catalogId = catalogId,
            items = items,
            availableItemCount = page.rawItemCount,
            supportsPagination = supportsPagination,
        )
    }

    private fun ensureCollectionHeroFallback(
        addons: List<ManagedAddon>,
        force: Boolean,
        requestKey: String?,
    ) {
        if (!lastPublishedCatalogHeroEmpty) return
        val snapshot = HomeCatalogSettingsRepository.snapshot()
        if (!snapshot.heroEnabled) return
        val collections = enabledCollectionsForHero(snapshot)
        if (collections.isEmpty()) {
            cachedCollectionHeroItems = emptyList()
            collectionHeroRequestKey = null
            return
        }

        val nextRequestKey = collectionHeroRequestKey(
            collections = collections,
            addons = addons,
            snapshot = snapshot,
            requestKey = requestKey,
        )
        if (!force && collectionHeroRequestKey == nextRequestKey) return

        collectionHeroJob?.cancel()
        collectionHeroRequestKey = nextRequestKey
        cachedCollectionHeroItems = emptyList()
        publishCurrentState(
            isLoading = _uiState.value.isLoading,
            requestKey = requestKey,
        )

        collectionHeroJob = scope.launch {
            val sources = collectionHeroSources(collections)
            val sourceResults = sources.map { source ->
                async {
                    runCatching {
                        source.resolveCollectionHeroItems(addons)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
            val random = Random((nextRequestKey.hashCode()).absoluteValue + 7)
            cachedCollectionHeroItems = roundRobinCollectionHeroItems(sourceResults)
                .distinctBy { item -> item.stableKey() }
                .shuffled(random)
                .take(HOME_HERO_ITEM_LIMIT)
            publishCurrentState(
                isLoading = _uiState.value.isLoading,
                requestKey = requestKey,
            )
        }
    }

    private fun enabledCollectionsForHero(snapshot: HomeCatalogSettingsSnapshot): List<Collection> {
        val preferences = snapshot.preferences
        return CollectionRepository.collections.value
            .filter { collection ->
                collection.folders.isNotEmpty() &&
                    preferences["collection_${collection.id}"]?.enabled != false
            }
            .sortedBy { collection ->
                preferences["collection_${collection.id}"]?.order ?: Int.MAX_VALUE
            }
    }

    private fun collectionHeroSources(collections: List<Collection>): List<CollectionSource> =
        collections
            .flatMap { collection -> collection.folders }
            .flatMap { folder -> folder.resolvedSources }
            .take(HOME_COLLECTION_HERO_SOURCE_LIMIT)

    private suspend fun CollectionSource.resolveCollectionHeroItems(addons: List<ManagedAddon>): List<MetaPreview> {
        val page = when {
            isTmdb -> TmdbCollectionSourceResolver.resolve(source = this, page = 1)
            isTrakt -> TraktPublicListSourceResolver.resolve(source = this, page = 1)
            else -> {
                val catalogSource = addonCatalogSource() ?: return emptyList()
                val resolvedCatalog = addons.findCollectionCatalog(catalogSource) ?: return emptyList()
                fetchCatalogPage(
                    manifestUrl = resolvedCatalog.addon.manifestUrl,
                    type = catalogSource.type,
                    catalogId = catalogSource.catalogId,
                    genre = catalogSource.genre,
                    maxItems = HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT,
                )
            }
        }
        val items = page.items
        return if (HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) {
            items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
        } else {
            items
        }
    }

    private fun roundRobinCollectionHeroItems(sourceResults: List<List<MetaPreview>>): List<MetaPreview> {
        val iterators = sourceResults.filter { it.isNotEmpty() }.map { it.iterator() }
        if (iterators.isEmpty()) return emptyList()
        val merged = mutableListOf<MetaPreview>()
        var hasMore = true
        while (hasMore && merged.size < HOME_COLLECTION_HERO_SOURCE_LIMIT * HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT) {
            hasMore = false
            iterators.forEach { iterator ->
                if (iterator.hasNext()) {
                    merged.add(iterator.next())
                    hasMore = true
                }
            }
        }
        return merged
    }

    private fun collectionHeroRequestKey(
        collections: List<Collection>,
        addons: List<ManagedAddon>,
        snapshot: HomeCatalogSettingsSnapshot,
        requestKey: String?,
    ): String = buildString {
        append(requestKey.orEmpty())
        append("|hideUnreleased=")
        append(snapshot.hideUnreleasedContent)
        append("|collections=")
        collections.forEach { collection ->
            val preference = snapshot.preferences["collection_${collection.id}"]
            append(collection.id)
            append(":")
            append(preference?.order ?: Int.MAX_VALUE)
            append(":")
            collection.folders.forEach { folder ->
                append(folder.id)
                append("[")
                folder.resolvedSources.forEach { source ->
                    append(collectionSourceKey(source))
                    append(",")
                }
                append("]")
            }
            append(";")
        }
        append("|addons=")
        addons.forEach { addon ->
            append(addon.manifest?.id.orEmpty())
            append(":")
            append(addon.manifestUrl)
            append(":")
            append(addon.manifest?.catalogs?.size ?: 0)
            append(";")
        }
    }

    private fun collectionSourceKey(source: CollectionSource): String =
        listOf(
            source.provider,
            source.addonId,
            source.type,
            source.catalogId,
            source.genre,
            source.tmdbSourceType,
            source.tmdbId?.toString(),
            source.traktListId?.toString(),
            source.mediaType,
            source.sortBy,
            source.sortHow,
        ).joinToString(":") { it.orEmpty() }
}

private const val HOME_HERO_ITEM_LIMIT = 8
private const val HOME_COLLECTION_HERO_SOURCE_LIMIT = 6
private const val HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT = 8
private const val HOME_CATALOG_FETCH_BATCH_SIZE = 4
private const val HOME_CATALOG_PREVIEW_FETCH_LIMIT = 18
private const val HOME_CATALOG_PUBLISH_INTERVAL = 2

private fun prioritizeDefinitions(
    definitions: List<HomeCatalogDefinition>,
    snapshot: HomeCatalogSettingsSnapshot,
): List<HomeCatalogDefinition> {
    val orderedDefinitions = definitions.sortedBy { definition ->
        snapshot.preferences[definition.key]?.order ?: Int.MAX_VALUE
    }
    val (priority, remainder) = orderedDefinitions.partition { definition ->
        val preference = snapshot.preferences[definition.key]
        if (preference == null) {
            true
        } else {
            preference.enabled || (snapshot.heroEnabled && preference.heroSourceEnabled)
        }
    }
    return priority + remainder
}
