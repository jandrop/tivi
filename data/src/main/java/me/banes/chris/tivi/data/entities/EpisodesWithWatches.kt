/*
 * Copyright 2018 Google, Inc.
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

package me.banes.chris.tivi.data.entities

import android.arch.persistence.room.Embedded
import android.arch.persistence.room.Relation
import java.util.Objects

class EpisodesWithWatches {
    @Embedded
    var episode: Episode? = null

    @Relation(parentColumn = "id", entityColumn = "episode_id")
    var watches: List<EpisodeWatchEntry> = emptyList()

    fun isWatched() = watches.isNotEmpty()

    override fun equals(other: Any?): Boolean = when {
        other === this -> true
        other is EpisodesWithWatches -> watches == other.watches && episode == other.episode
        else -> false
    }

    override fun hashCode(): Int = Objects.hash(episode, watches)
}