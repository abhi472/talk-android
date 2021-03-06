/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
 * Copyright (C) 2017 Mario Danic <mario@lovelyhq.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.events;

import android.support.annotation.Nullable;

import com.nextcloud.talk.api.models.json.signaling.NCIceCandidate;

import org.webrtc.SessionDescription;

import lombok.Data;

@Data
public class SessionDescriptionSendEvent {
    @Nullable
    private final SessionDescription sessionDescription;
    private final String peerId;
    private final String type;
    @Nullable
    private final NCIceCandidate ncIceCandidate;

    public SessionDescriptionSendEvent(@Nullable SessionDescription sessionDescription, String peerId, String type,
                                       @Nullable NCIceCandidate ncIceCandidate) {
        this.sessionDescription = sessionDescription;
        this.peerId = peerId;
        this.type = type;
        this.ncIceCandidate = ncIceCandidate;
    }
}
