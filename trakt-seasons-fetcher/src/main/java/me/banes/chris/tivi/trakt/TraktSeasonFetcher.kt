/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.banes.chris.tivi.trakt

import com.uwetrottmann.trakt5.TraktV2
import com.uwetrottmann.trakt5.enums.Extended
import kotlinx.coroutines.experimental.withContext
import me.banes.chris.tivi.data.DatabaseTransactionRunner
import me.banes.chris.tivi.data.daos.EntityInserter
import me.banes.chris.tivi.data.daos.EpisodesDao
import me.banes.chris.tivi.data.daos.SeasonsDao
import me.banes.chris.tivi.data.daos.TiviShowDao
import me.banes.chris.tivi.data.entities.Episode
import me.banes.chris.tivi.data.entities.Season
import me.banes.chris.tivi.extensions.fetchBodyWithRetry
import me.banes.chris.tivi.util.AppCoroutineDispatchers
import org.threeten.bp.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import com.uwetrottmann.trakt5.entities.Episode as TraktEpisode
import com.uwetrottmann.trakt5.entities.Season as TraktSeason

@Singleton
class TraktSeasonFetcher @Inject constructor(
    private val showDao: TiviShowDao,
    private val seasonDao: SeasonsDao,
    private val episodesDao: EpisodesDao,
    private val trakt: TraktV2,
    private val dispatchers: AppCoroutineDispatchers,
    private val entityInserter: EntityInserter,
    private val transactionRunner: DatabaseTransactionRunner
) {
    suspend fun updateSeasonData(showId: Long) {
        val show = withContext(dispatchers.database) {
            showDao.getShowWithId(showId)
        } ?: throw IllegalArgumentException("Show with id[$showId] does not exist")

        val response = withContext(dispatchers.network) {
            trakt.seasons().summary(show.traktId!!.toString(), Extended.FULLEPISODES).fetchBodyWithRetry()
        }

        withContext(dispatchers.database) {
            transactionRunner.runInTransaction {
                response.forEach { traktSeason ->
                    // Upsert the season
                    val seasonId = upsertSeason(showId, traktSeason)
                    // Now upsert all the episodes
                    traktSeason.episodes?.forEach {
                        upsertEpisode(seasonId, it)
                    }
                }
            }
        }
    }

    private fun upsertSeason(showId: Long, traktSeason: TraktSeason): Long {
        return (seasonDao.seasonWithSeasonTraktId(traktSeason.ids.trakt) ?: Season()).apply {
            updateProperty(this::showId, showId)
            updateProperty(this::traktId, traktSeason.ids.trakt)
            updateProperty(this::tmdbId, traktSeason.ids.tmdb)
            updateProperty(this::number, traktSeason.number)
            updateProperty(this::title, traktSeason.title)
            updateProperty(this::summary, traktSeason.overview)
            updateProperty(this::rating, traktSeason.rating?.toFloat())
            updateProperty(this::votes, traktSeason.votes)
            updateProperty(this::episodeCount, traktSeason.episode_count)
            updateProperty(this::airedEpisodes, traktSeason.aired_episodes)
            updateProperty(this::network, traktSeason.network)
            lastTraktUpdate = OffsetDateTime.now()
        }.let {
            entityInserter.insertOrUpdate(seasonDao, it)
        }
    }

    private fun upsertEpisode(seasonId: Long, traktEpisode: TraktEpisode) {
        (episodesDao.episodeWithTraktId(traktEpisode.ids.trakt) ?: Episode()).apply {
            updateProperty(this::seasonId, seasonId)
            updateProperty(this::traktId, traktEpisode.ids.trakt)
            updateProperty(this::tmdbId, traktEpisode.ids.tmdb)
            updateProperty(this::title, traktEpisode.title)
            updateProperty(this::number, traktEpisode.number)
            updateProperty(this::summary, traktEpisode.overview)
            updateProperty(this::firstAired, traktEpisode.first_aired)
            updateProperty(this::rating, traktEpisode.rating?.toFloat())
            updateProperty(this::votes, traktEpisode.votes)
            lastTraktUpdate = OffsetDateTime.now()
        }.also {
            entityInserter.insertOrUpdate(episodesDao, it)
        }
    }
}