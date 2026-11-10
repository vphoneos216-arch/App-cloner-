package app.gamecloner;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Rows of icon, label, package line and an optional per-row action button. */
final class AppListAdapter extends BaseAdapter {
    interface ActionListener {
        void onAction(AppEntry entry);
    }

    private static final class Row {
        ImageView icon;
        TextView title;
        TextView subtitle;
        Button action;
    }

    private final Context context;
    private final String actionLabel;
    private final ActionListener listener;
    private final List<AppEntry> items = new ArrayList<>();

    /**
     * @param actionLabel text of the per-row button, or null for rows without a button.
     *                    System apps never get the button (they cannot be uninstalled).
     */
    AppListAdapter(Context context, String actionLabel, ActionListener listener) {
        this.context = context;
        this.actionLabel = actionLabel;
        this.listener = listener;
    }

    void setItems(List<AppEntry> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public AppEntry getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView != null ? convertView : createRow();
        Row row = (Row) view.getTag();
        final AppEntry entry = items.get(position);

        row.icon.setImageDrawable(entry.icon);
        row.title.setText(entry.label);
        row.subtitle.setText(entry.describe());

        if (actionLabel == null || listener == null || entry.system) {
            row.action.setVisibility(View.GONE);
            row.action.setOnClickListener(null);
        } else {
            row.action.setVisibility(View.VISIBLE);
            row.action.setText(actionLabel);
            row.action.setOnClickListener(v -> listener.onAction(entry));
        }
        return view;
    }

    private View createRow() {
        int pad = Ui.dp(context, 12);
        int iconSize = Ui.dp(context, 44);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        // Without this the button would take focus and the row itself could not be tapped.
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        Row row = new Row();

        row.icon = new ImageView(context);
        root.addView(row.icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(pad, 0, pad, 0);

        row.title = new TextView(context);
        row.title.setTextSize(16f);
        row.title.setSingleLine(true);
        texts.addView(row.title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.subtitle = new TextView(context);
        row.subtitle.setTextSize(12f);
        row.subtitle.setAlpha(0.7f);
        row.subtitle.setSingleLine(true);
        texts.addView(row.subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.action = new Button(context);
        row.action.setFocusable(false);
        root.addView(row.action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.setTag(row);
        return root;
    }
}
