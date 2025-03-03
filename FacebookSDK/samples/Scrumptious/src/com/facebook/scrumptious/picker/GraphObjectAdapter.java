/**
 * Copyright (c) 2014-present, Facebook, Inc. All rights reserved.
 *
 * You are hereby granted a non-exclusive, worldwide, royalty-free license to use,
 * copy, modify, and distribute this software in source code or binary form for use
 * in connection with the web services and APIs provided by Facebook.
 *
 * As with any software that integrates with the Facebook platform, your use of
 * this software is subject to the Facebook Developer Principles and Policies
 * [http://developers.facebook.com/policy/]. This copyright notice shall be
 * included in all copies or substantial portions of the software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.facebook.scrumptious.picker;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import com.facebook.FacebookException;
import com.facebook.internal.ImageDownloader;
import com.facebook.internal.ImageRequest;
import com.facebook.internal.ImageResponse;
import com.facebook.internal.Utility;
import com.facebook.scrumptious.R;

import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class GraphObjectAdapter extends BaseAdapter implements SectionIndexer {
    private static final int DISPLAY_SECTIONS_THRESHOLD = 1;
    private static final int HEADER_VIEW_TYPE = 0;
    private static final int GRAPH_OBJECT_VIEW_TYPE = 1;
    private static final int ACTIVITY_CIRCLE_VIEW_TYPE = 2;
    private static final int MAX_PREFETCHED_PICTURES = 20;

    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String PICTURE = "picture";

    private final Map<String, ImageRequest> pendingRequests = new HashMap<String, ImageRequest>();
    private final LayoutInflater inflater;
    private List<String> sectionKeys = new ArrayList<String>();
    private Map<String, ArrayList<JSONObject>> graphObjectsBySection = new HashMap<String, ArrayList<JSONObject>>();
    private Map<String, JSONObject> graphObjectsById = new HashMap<String, JSONObject>();
    private boolean displaySections;
    private List<String> sortFields;
    private String groupByField;
    private boolean showPicture;
    private boolean showCheckbox;
    private Filter filter;
    private DataNeededListener dataNeededListener;
    private GraphObjectCursor cursor;
    private Context context;
    private Map<String, ImageResponse> prefetchedPictureCache = new HashMap<String, ImageResponse>();
    private ArrayList<String> prefetchedProfilePictureIds = new ArrayList<String>();
    private OnErrorListener onErrorListener;

    public interface DataNeededListener {
        public void onDataNeeded();
    }

    public interface OnErrorListener {
        void onError(GraphObjectAdapter adapter, FacebookException error);
    }

    public static class SectionAndItem {
        public String sectionKey;
        public JSONObject graphObject;

        public enum Type {
            GRAPH_OBJECT,
            SECTION_HEADER,
            ACTIVITY_CIRCLE
        }

        public SectionAndItem(String sectionKey, JSONObject graphObject) {
            this.sectionKey = sectionKey;
            this.graphObject = graphObject;
        }

        public Type getType() {
            if (sectionKey == null) {
                return Type.ACTIVITY_CIRCLE;
            } else if (graphObject == null) {
                return Type.SECTION_HEADER;
            } else {
                return Type.GRAPH_OBJECT;
            }
        }
    }

    interface Filter {
        boolean includeItem(JSONObject graphObject);
    }

    public GraphObjectAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public List<String> getSortFields() {
        return sortFields;
    }

    public void setSortFields(List<String> sortFields) {
        this.sortFields = sortFields;
    }

    public String getGroupByField() {
        return groupByField;
    }

    public void setGroupByField(String groupByField) {
        this.groupByField = groupByField;
    }

    public boolean getShowPicture() {
        return showPicture;
    }

    public void setShowPicture(boolean showPicture) {
        this.showPicture = showPicture;
    }

    public boolean getShowCheckbox() {
        return showCheckbox;
    }

    public void setShowCheckbox(boolean showCheckbox) {
        this.showCheckbox = showCheckbox;
    }

    public DataNeededListener getDataNeededListener() {
        return dataNeededListener;
    }

    public void setDataNeededListener(DataNeededListener dataNeededListener) {
        this.dataNeededListener = dataNeededListener;
    }

    public OnErrorListener getOnErrorListener() {
        return onErrorListener;
    }

    public void setOnErrorListener(OnErrorListener onErrorListener) {
        this.onErrorListener = onErrorListener;
    }

    public GraphObjectCursor getCursor() {
        return cursor;
    }

    public boolean changeCursor(GraphObjectCursor cursor) {
        if (this.cursor == cursor) {
            return false;
        }
        if (this.cursor != null) {
            this.cursor.close();
        }
        this.cursor = cursor;

        rebuildAndNotify();
        return true;
    }

    public void rebuildAndNotify() {
        rebuildSections();
        notifyDataSetChanged();
    }

    public void prioritizeViewRange(int firstVisibleItem, int lastVisibleItem, int prefetchBuffer) {
        if ((lastVisibleItem < firstVisibleItem) || (sectionKeys.size() == 0)) {
            return;
        }

        // We want to prioritize requests for items which are visible but do not have pictures
        // loaded yet. We also want to pre-fetch pictures for items which are not yet visible
        // but are within a buffer on either side of the visible items, on the assumption that
        // they will be visible soon. For these latter items, we'll store the images in memory
        // in the hopes we can immediately populate their image view when needed.

        // Prioritize the requests in reverse order since each call to prioritizeRequest will just
        // move it to the front of the queue. And we want the earliest ones in the range to be at
        // the front of the queue, so all else being equal, the list will appear to populate from
        // the top down.
        for (int i = lastVisibleItem; i >= 0; i--) {
            SectionAndItem sectionAndItem = getSectionAndItem(i);
            if (sectionAndItem.graphObject != null) {
                String id = getIdOfGraphObject(sectionAndItem.graphObject);
                ImageRequest request = pendingRequests.get(id);
                if (request != null) {
                    ImageDownloader.prioritizeRequest(request);
                }
            }
        }

        // For items which are not visible, but within the buffer on either side, we want to
        // fetch those items and store them in a small in-memory cache of bitmaps.
        int start = Math.max(0, firstVisibleItem - prefetchBuffer);
        int end = Math.min(lastVisibleItem + prefetchBuffer, getCount() - 1);
        ArrayList<JSONObject> graphObjectsToPrefetchPicturesFor = new ArrayList<JSONObject>();
        // Add the IDs before and after the visible range.
        for (int i = start; i < firstVisibleItem; ++i) {
            SectionAndItem sectionAndItem = getSectionAndItem(i);
            if (sectionAndItem.graphObject != null) {
                graphObjectsToPrefetchPicturesFor.add(sectionAndItem.graphObject);
            }
        }
        for (int i = lastVisibleItem + 1; i <= end; ++i) {
            SectionAndItem sectionAndItem = getSectionAndItem(i);
            if (sectionAndItem.graphObject != null) {
                graphObjectsToPrefetchPicturesFor.add(sectionAndItem.graphObject);
            }
        }
        for (JSONObject graphObject : graphObjectsToPrefetchPicturesFor) {
            Uri uri = getPictureUriOfGraphObject(graphObject);
            final String id = getIdOfGraphObject(graphObject);

            // This URL already have been requested for pre-fetching, but we want to act in an LRU manner, so move
            // it to the end of the list regardless.
            boolean alreadyPrefetching = prefetchedProfilePictureIds.remove(id);
            prefetchedProfilePictureIds.add(id);

            // If we've already requested it for pre-fetching, no need to do so again.
            if (!alreadyPrefetching) {
                downloadProfilePicture(id, uri, null);
            }
        }
    }

    protected String getSectionKeyOfGraphObject(JSONObject graphObject) {
        String result = null;

        if (groupByField != null) {
            result = graphObject.optString(groupByField);
            if (result != null && result.length() > 0) {
                result = result.substring(0, 1).toUpperCase();
            }
        }

        return (result != null) ? result : "";
    }

    protected CharSequence getTitleOfGraphObject(JSONObject graphObject) {
        return graphObject.optString(NAME);
    }

    protected CharSequence getSubTitleOfGraphObject(JSONObject graphObject) {
        return null;
    }

    protected Uri getPictureUriOfGraphObject(JSONObject graphObject) {
        String uri = null;
        Object o = graphObject.opt(PICTURE);
        if (o instanceof String) {
            uri = (String) o;
        } else if (o instanceof JSONObject) {
            JSONObject data = ((JSONObject) o).optJSONObject("data");
            uri = data != null ? data.optString("url") : null;
        }

        if (uri != null) {
            return Uri.parse(uri);
        }
        return null;
    }

    protected View getSectionHeaderView(String sectionHeader, View convertView, ViewGroup parent) {
        TextView result = (TextView) convertView;

        if (result == null) {
            result = (TextView) inflater.inflate(R.layout.picker_list_section_header, null);
        }

        result.setText(sectionHeader);

        return result;
    }

    protected View getGraphObjectView(JSONObject graphObject, View convertView, ViewGroup parent) {
        View result = convertView;

        if (result == null) {
            result = createGraphObjectView(graphObject);
        }

        populateGraphObjectView(result, graphObject);
        return result;
    }

    private View getActivityCircleView(View convertView, ViewGroup parent) {
        View result = convertView;

        if (result == null) {
            result = inflater.inflate(R.layout.picker_activity_circle_row, null);
        }
        ProgressBar activityCircle = (ProgressBar) result.findViewById(R.id.com_facebook_picker_row_activity_circle);
        activityCircle.setVisibility(View.VISIBLE);

        return result;
    }

    protected int getGraphObjectRowLayoutId(JSONObject graphObject) {
        return R.layout.picker_list_row;
    }

    protected int getDefaultPicture() {
        return R.drawable.profile_default_icon;
    }

    protected View createGraphObjectView(JSONObject graphObject) {
        View result = inflater.inflate(getGraphObjectRowLayoutId(graphObject), null);

        ViewStub checkboxStub = (ViewStub) result.findViewById(R.id.com_facebook_picker_checkbox_stub);
        if (checkboxStub != null) {
            if (!getShowCheckbox()) {
                checkboxStub.setVisibility(View.GONE);
            } else {
                CheckBox checkBox = (CheckBox) checkboxStub.inflate();
                updateCheckboxState(checkBox, false);
            }
        }

        ViewStub profilePicStub = (ViewStub) result.findViewById(R.id.com_facebook_picker_profile_pic_stub);
        if (!getShowPicture()) {
            profilePicStub.setVisibility(View.GONE);
        } else {
            ImageView imageView = (ImageView) profilePicStub.inflate();
            imageView.setVisibility(View.VISIBLE);
        }

        return result;
    }

    protected void populateGraphObjectView(View view, JSONObject graphObject) {
        String id = getIdOfGraphObject(graphObject);
        view.setTag(id);

        CharSequence title = getTitleOfGraphObject(graphObject);
        TextView titleView = (TextView) view.findViewById(R.id.com_facebook_picker_title);
        if (titleView != null) {
            titleView.setText(title, TextView.BufferType.SPANNABLE);
        }

        CharSequence subtitle = getSubTitleOfGraphObject(graphObject);
        TextView subtitleView = (TextView) view.findViewById(R.id.picker_subtitle);
        if (subtitleView != null) {
            if (subtitle != null) {
                subtitleView.setText(subtitle, TextView.BufferType.SPANNABLE);
                subtitleView.setVisibility(View.VISIBLE);
            } else {
                subtitleView.setVisibility(View.GONE);
            }
        }

        if (getShowCheckbox()) {
            CheckBox checkBox = (CheckBox) view.findViewById(R.id.com_facebook_picker_checkbox);
            updateCheckboxState(checkBox, isGraphObjectSelected(id));
        }

        if (getShowPicture()) {
            Uri pictureURI = getPictureUriOfGraphObject(graphObject);

            if (pictureURI != null) {
                ImageView profilePic = (ImageView) view.findViewById(R.id.com_facebook_picker_image);

                // See if we have already pre-fetched this; if not, download it.
                if (prefetchedPictureCache.containsKey(id)) {
                    ImageResponse response = prefetchedPictureCache.get(id);
                    profilePic.setImageBitmap(response.getBitmap());
                    profilePic.setTag(response.getRequest().getImageUri());
                } else {
                    downloadProfilePicture(id, pictureURI, profilePic);
                }
            }
        }
    }

    /**
     * @throws FacebookException if the GraphObject doesn't have an ID.
     */
    String getIdOfGraphObject(JSONObject graphObject) {
        String id = graphObject.optString(ID);
        if (id != null) {
            return id;
        }
        throw new FacebookException("Received an object without an ID.");
    }

    boolean filterIncludesItem(JSONObject graphObject) {
        return filter == null || filter.includeItem(graphObject);
    }

    Filter getFilter() {
        return filter;
    }

    void setFilter(Filter filter) {
        this.filter = filter;
    }

    boolean isGraphObjectSelected(String graphObjectId) {
        return false;
    }

    void updateCheckboxState(CheckBox checkBox, boolean graphObjectSelected) {
        // Default is no-op
    }

    String getPictureFieldSpecifier() {
        // How big is our image?
        View view = createGraphObjectView(null);
        ImageView picture = (ImageView) view.findViewById(R.id.com_facebook_picker_image);
        if (picture == null) {
            return null;
        }

        // Note: these dimensions are in pixels, not dips
        ViewGroup.LayoutParams layoutParams = picture.getLayoutParams();
        return String.format(Locale.US, "picture.height(%d).width(%d)", layoutParams.height, layoutParams.width);
    }


    private boolean shouldShowActivityCircleCell() {
        // We show the "more data" activity circle cell if we have a listener to request more data,
        // we are expecting more data, and we have some data already (i.e., not on a fresh query).
        return (cursor != null) && cursor.areMoreObjectsAvailable() && (dataNeededListener != null) && !isEmpty();
    }

    private void rebuildSections() {
        sectionKeys = new ArrayList<String>();
        graphObjectsBySection = new HashMap<String, ArrayList<JSONObject>>();
        graphObjectsById = new HashMap<String, JSONObject>();
        displaySections = false;

        if (cursor == null || cursor.getCount() == 0) {
            return;
        }

        int objectsAdded = 0;
        cursor.moveToFirst();
        do {
            JSONObject graphObject = cursor.getGraphObject();

            if (!filterIncludesItem(graphObject)) {
                continue;
            }

            objectsAdded++;

            String sectionKeyOfItem = getSectionKeyOfGraphObject(graphObject);
            if (!graphObjectsBySection.containsKey(sectionKeyOfItem)) {
                sectionKeys.add(sectionKeyOfItem);
                graphObjectsBySection.put(sectionKeyOfItem, new ArrayList<JSONObject>());
            }
            List<JSONObject> section = graphObjectsBySection.get(sectionKeyOfItem);
            section.add(graphObject);

            graphObjectsById.put(getIdOfGraphObject(graphObject), graphObject);
        } while (cursor.moveToNext());

        if (sortFields != null) {
            final Collator collator = Collator.getInstance();
            for (List<JSONObject> section : graphObjectsBySection.values()) {
                Collections.sort(section, new Comparator<JSONObject>() {
                    @Override
                    public int compare(JSONObject a, JSONObject b) {
                        return compareGraphObjects(a, b, sortFields, collator);
                    }
                });
            }
        }

        Collections.sort(sectionKeys, Collator.getInstance());

        displaySections = sectionKeys.size() > 1 && objectsAdded > DISPLAY_SECTIONS_THRESHOLD;
    }

    SectionAndItem getSectionAndItem(int position) {
        if (sectionKeys.size() == 0) {
            return null;
        }
        String sectionKey = null;
        JSONObject graphObject = null;

        if (!displaySections) {
            sectionKey = sectionKeys.get(0);
            List<JSONObject> section = graphObjectsBySection.get(sectionKey);
            if (position >= 0 && position < section.size()) {
                graphObject = graphObjectsBySection.get(sectionKey).get(position);
            } else {
                // We are off the end; we must be adding an activity circle to indicate more data is coming.
                assert dataNeededListener != null && cursor.areMoreObjectsAvailable();
                // We return null for both to indicate this.
                return new SectionAndItem(null, null);
            }
        } else {
            // Count through the sections; the "0" position in each section is the header. We decrement
            // position each time we skip forward a certain number of elements, including the header.
            for (String key : sectionKeys) {
                // Decrement if we skip over the header
                if (position-- == 0) {
                    sectionKey = key;
                    break;
                }

                List<JSONObject> section = graphObjectsBySection.get(key);
                if (position < section.size()) {
                    // The position is somewhere in this section. Get the corresponding graph object.
                    sectionKey = key;
                    graphObject = section.get(position);
                    break;
                }
                // Decrement by as many items as we skipped over
                position -= section.size();
            }
        }
        if (sectionKey != null) {
            // Note: graphObject will be null if this represents a section header.
            return new SectionAndItem(sectionKey, graphObject);
        } else {
            throw new IndexOutOfBoundsException("position");
        }
    }

    int getPosition(String sectionKey, JSONObject graphObject) {
        int position = 0;
        boolean found = false;

        // First find the section key and increment position one for each header we will render;
        // increment by the size of each section prior to the one we want.
        for (String key : sectionKeys) {
            if (displaySections) {
                position++;
            }
            if (key.equals(sectionKey)) {
                found = true;
                break;
            } else {
                position += graphObjectsBySection.get(key).size();
            }
        }

        if (!found) {
            return -1;
        } else if (graphObject == null) {
            // null represents the header for a section; we counted this header in position earlier,
            // so subtract it back out.
            return position - (displaySections ? 1 : 0);
        }

        // Now find index of this item within that section.
        for (JSONObject t : graphObjectsBySection.get(sectionKey)) {
            if (Utility.hasSameId(t, graphObject)) {
                return position;
            }
            position++;
        }
        return -1;
    }

    @Override
    public boolean isEmpty() {
        // We'll never populate sectionKeys unless we have at least one object.
        return sectionKeys.size() == 0;
    }

    @Override
    public int getCount() {
        if (sectionKeys.size() == 0) {
            return 0;
        }

        // If we are not displaying sections, we don't display a header; otherwise, we have one header per item in
        // addition to the actual items.
        int count = (displaySections) ? sectionKeys.size() : 0;
        for (List<JSONObject> section : graphObjectsBySection.values()) {
            count += section.size();
        }

        // If we should show a cell with an activity circle indicating more data is coming, add it to the count.
        if (shouldShowActivityCircleCell()) {
            ++count;
        }

        return count;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return displaySections;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public boolean isEnabled(int position) {
        SectionAndItem sectionAndItem = getSectionAndItem(position);
        return sectionAndItem.getType() == SectionAndItem.Type.GRAPH_OBJECT;
    }

    @Override
    public Object getItem(int position) {
        SectionAndItem sectionAndItem = getSectionAndItem(position);
        return (sectionAndItem.getType() == SectionAndItem.Type.GRAPH_OBJECT) ? sectionAndItem.graphObject : null;
    }

    @Override
    public long getItemId(int position) {
        // We assume IDs that can be converted to longs. If this is not the case for certain types of
        // GraphObjects, subclasses should override this to return, e.g., position, and override hasStableIds
        // to return false.
        SectionAndItem sectionAndItem = getSectionAndItem(position);
        if (sectionAndItem != null && sectionAndItem.graphObject != null) {
            String id = getIdOfGraphObject(sectionAndItem.graphObject);
            if (id != null) {
                try {
                    return Long.parseLong(id);
                } catch (NumberFormatException e) {
                    // NOOP
                }
            }
        }
        return 0;
    }

    @Override
    public int getViewTypeCount() {
        return 3;
    }

    @Override
    public int getItemViewType(int position) {
        SectionAndItem sectionAndItem = getSectionAndItem(position);
        switch (sectionAndItem.getType()) {
            case SECTION_HEADER:
                return HEADER_VIEW_TYPE;
            case GRAPH_OBJECT:
                return GRAPH_OBJECT_VIEW_TYPE;
            case ACTIVITY_CIRCLE:
                return ACTIVITY_CIRCLE_VIEW_TYPE;
            default:
                throw new FacebookException("Unexpected type of section and item.");
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        SectionAndItem sectionAndItem = getSectionAndItem(position);

        switch (sectionAndItem.getType()) {
            case SECTION_HEADER:
                return getSectionHeaderView(sectionAndItem.sectionKey, convertView, parent);
            case GRAPH_OBJECT:
                return getGraphObjectView(sectionAndItem.graphObject, convertView, parent);
            case ACTIVITY_CIRCLE:
                // If we get a request for this view, it means we need more data.
                assert cursor.areMoreObjectsAvailable() && (dataNeededListener != null);
                dataNeededListener.onDataNeeded();
                return getActivityCircleView(convertView, parent);
            default:
                throw new FacebookException("Unexpected type of section and item.");
        }
    }

    @Override
    public Object[] getSections() {
        if (displaySections) {
            return sectionKeys.toArray();
        } else {
            return new Object[0];
        }
    }

    @Override
    public int getPositionForSection(int section) {
        if (displaySections) {
            section = Math.max(0, Math.min(section, sectionKeys.size() - 1));
            if (section < sectionKeys.size()) {
                return getPosition(sectionKeys.get(section), null);
            }
        }
        return 0;
    }

    @Override
    public int getSectionForPosition(int position) {
        SectionAndItem sectionAndItem = getSectionAndItem(position);
        if (sectionAndItem != null &&
                sectionAndItem.getType() != SectionAndItem.Type.ACTIVITY_CIRCLE) {
            return Math.max(0, Math.min(sectionKeys.indexOf(sectionAndItem.sectionKey), sectionKeys.size() - 1));
        }
        return 0;
    }

    public List<JSONObject> getGraphObjectsById(Collection<String> ids) {
        Set<String> idSet = new HashSet<String>();
        idSet.addAll(ids);

        ArrayList<JSONObject> result = new ArrayList<JSONObject>(idSet.size());
        for (String id : idSet) {
            JSONObject graphObject = graphObjectsById.get(id);
            if (graphObject != null) {
                result.add(graphObject);
            }
        }

        return result;
    }

    private void downloadProfilePicture(
            final String profileId,
            Uri pictureUri,
            final ImageView imageView) {
        if (pictureUri == null) {
            return;
        }

        // If we don't have an imageView, we are pre-fetching this image to store in-memory because we
        // think the user might scroll to its corresponding list row. If we do have an imageView, we
        // only want to queue a download if the view's tag isn't already set to the URL (which would mean
        // it's already got the correct picture).
        boolean prefetching = imageView == null;
        if (prefetching || !pictureUri.equals(imageView.getTag())) {
            if (!prefetching) {
                // Setting the tag to the profile ID indicates that we're currently downloading the
                // picture for this profile; we'll set it to the actual picture URL when complete.
                imageView.setTag(profileId);
                imageView.setImageResource(getDefaultPicture());
            }

            ImageRequest.Builder builder = new ImageRequest.Builder(
                    context.getApplicationContext(),
                    pictureUri)
                    .setCallerTag(this)
                    .setCallback(
                            new ImageRequest.Callback() {
                                @Override
                                public void onCompleted(ImageResponse response) {
                                    processImageResponse(response, profileId, imageView);
                                }
                            });

            ImageRequest newRequest = builder.build();
            pendingRequests.put(profileId, newRequest);

            ImageDownloader.downloadAsync(newRequest);
        }
    }

    private void callOnErrorListener(Exception exception) {
        if (onErrorListener != null) {
            if (!(exception instanceof FacebookException)) {
                exception = new FacebookException(exception);
            }
            onErrorListener.onError(this, (FacebookException) exception);
        }
    }

    private void processImageResponse(ImageResponse response, String graphObjectId, ImageView imageView) {
        pendingRequests.remove(graphObjectId);
        if (response.getError() != null) {
            callOnErrorListener(response.getError());
        }

        if (imageView == null) {
            // This was a pre-fetch request.
            if (response.getBitmap() != null) {
                // Is the cache too big?
                if (prefetchedPictureCache.size() >= MAX_PREFETCHED_PICTURES) {
                    // Find the oldest one and remove it.
                    String oldestId = prefetchedProfilePictureIds.remove(0);
                    prefetchedPictureCache.remove(oldestId);
                }
                prefetchedPictureCache.put(graphObjectId, response);
            }
        } else if (graphObjectId.equals(imageView.getTag())) {
            Exception error = response.getError();
            Bitmap bitmap = response.getBitmap();
            if (error == null && bitmap != null) {
                imageView.setImageBitmap(bitmap);
                imageView.setTag(response.getRequest().getImageUri());
            }
        }
    }

    private static int compareGraphObjects(JSONObject a, JSONObject b, Collection<String> sortFields,
            Collator collator) {
        for (String sortField : sortFields) {
            String sa = a.optString(sortField);
            String sb = b.optString(sortField);

            if (sa != null && sb != null) {
                int result = collator.compare(sa, sb);
                if (result != 0) {
                    return result;
                }
            } else if (!(sa == null && sb == null)) {
                return (sa == null) ? -1 : 1;
            }
        }
        return 0;
    }
}
