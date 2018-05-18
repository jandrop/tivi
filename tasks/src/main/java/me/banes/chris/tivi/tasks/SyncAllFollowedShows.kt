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

package me.banes.chris.tivi.tasks

import com.evernote.android.job.Job
import com.evernote.android.job.JobRequest
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.withContext
import me.banes.chris.tivi.SeasonFetcher
import me.banes.chris.tivi.actions.ShowTasks
import me.banes.chris.tivi.data.daos.FollowedShowsDao
import me.banes.chris.tivi.util.AppCoroutineDispatchers
import javax.inject.Inject

class SyncAllFollowedShows @Inject constructor(
    private val followedShowsDao: FollowedShowsDao,
    private val dispatchers: AppCoroutineDispatchers,
    private val seasonFetcher: SeasonFetcher,
    private val showTasks: ShowTasks
) : Job() {
    companion object {
        const val TAG = "sync-all-followed-shows"

        fun buildRequest(): JobRequest.Builder {
            return JobRequest.Builder(TAG)
        }
    }

    override fun onRunJob(params: Params): Result {
        // TODO this is really crude, needs to be improved
        runBlocking {
            val followedShows = withContext(dispatchers.database) {
                followedShowsDao.entriesBlocking()
            }
            followedShows.forEach {
                seasonFetcher.load(it.showId)
                showTasks.syncShowWatchedEpisodes(it.showId)
            }
        }

        return Result.SUCCESS
    }
}