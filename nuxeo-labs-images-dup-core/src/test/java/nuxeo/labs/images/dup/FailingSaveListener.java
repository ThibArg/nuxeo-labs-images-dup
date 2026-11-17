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

import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;

/**
 * Makes the save of the documents named {@link #DOC_NAME} fail, the way a validation listener does: the exception
 * bubbles out of {@code saveDocument} without marking the transaction rollback-only.
 */
public class FailingSaveListener implements EventListener {

    public static final String DOC_NAME = "unsavable";

    @Override
    public void handleEvent(Event event) {
        if (event.getContext() instanceof DocumentEventContext ctx
                && DOC_NAME.equals(ctx.getSourceDocument().getName())) {
            event.markBubbleException();
            throw new NuxeoException("Simulated save failure on document: " + ctx.getSourceDocument().getId());
        }
    }
}
