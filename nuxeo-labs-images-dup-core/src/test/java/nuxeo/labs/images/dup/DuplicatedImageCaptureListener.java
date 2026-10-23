/*
 * (C) Copyright 2026 Nuxeo (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     Thibaud Arguillere
 */
package nuxeo.labs.images.dup;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;

/**
 * Captures the {@link ImageHashService#DUPLICATED_IMAGE_EVENT} events.
 */
public class DuplicatedImageCaptureListener implements EventListener {

    protected static final List<Event> EVENTS = new CopyOnWriteArrayList<>();

    @Override
    public void handleEvent(Event event) {
        EVENTS.add(event);
    }

    public static List<Event> getEvents() {
        return EVENTS;
    }

    public static void clear() {
        EVENTS.clear();
    }
}
