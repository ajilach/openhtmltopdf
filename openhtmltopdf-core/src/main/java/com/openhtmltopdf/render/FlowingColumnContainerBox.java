package com.openhtmltopdf.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.openhtmltopdf.css.constants.CSSName;
import com.openhtmltopdf.css.constants.IdentValue;
import com.openhtmltopdf.layout.BlockFormattingContext;
import com.openhtmltopdf.layout.FloatManager;
import com.openhtmltopdf.layout.FloatManager.BoxOffset;
import com.openhtmltopdf.layout.LayoutContext;
import com.openhtmltopdf.layout.PersistentBFC;

public class FlowingColumnContainerBox extends BlockBox {
    private FlowingColumnBox _child;

    // FIXME: Inefficient, replace with binary search.
    private int findPageIndex(List<PageBox> pages, int y) {
        int idx = 0;
        for (PageBox page : pages) {
            if (y >= page.getTop() && y <= page.getBottom()) {
                return idx;
            }
            idx++;
        }
        return idx - 1;
    }

    private static class ColumnPosition {
        private final int columnIndex;
        private final int copyY;  // Absolute, What y position starts the column in the long column block.
        private final int pasteY; // Absolute, What y position starts the column in the flowing column block for
                                  // final render.
        private final int maxColHeight; // Absolute, Maximum height of the column.
        private final int pageIdx;

        private ColumnPosition(int columnIndex, int copyY, int pasteY, int maxColHeight, int pageIdx) {
            this.columnIndex = columnIndex;
            this.copyY = copyY;
            this.pasteY = pasteY;
            this.maxColHeight = maxColHeight;
            this.pageIdx = pageIdx;
        }
        
        @Override
        public String toString() {
            return String.format("[index='%d', copyY='%d', pasteY='%d', maxColHeight='%d', pageIdx='%d']",
                           columnIndex, copyY, pasteY, maxColHeight, pageIdx);
        }
    }

    private static class BreakMetrics {
        private final ColumnBreakOpportunity breakOpportunity;
        private final int absY;
        private final int borderBoxHeight;
        // Whether the break between the previous opportunity and this one is to be avoided.
        private boolean avoidBreakBefore;

        private BreakMetrics(ColumnBreakOpportunity breakOpportunity, LayoutContext c) {
            this.breakOpportunity = breakOpportunity;
            this.absY = breakOpportunity.box.getAbsY();
            this.borderBoxHeight = breakOpportunity.box.getBorderBoxHeight(c);
        }

        private int bottom() {
            return absY + borderBoxHeight;
        }
    }

    private static class LayoutRun {
        private final int finalHeight;
        private final int columnsUsed;
        private final int lastPageIdx;

        private LayoutRun(int finalHeight, int columnsUsed, int lastPageIdx) {
            this.finalHeight = finalHeight;
            this.columnsUsed = columnsUsed;
            this.lastPageIdx = lastPageIdx;
        }
    }

    /**
     * The height the columns of the balanced fragment are capped to, and which fragment that is.
     * CSS Multi-column Layout balances the LAST fragment only; see {@link #findBalancedColumnHeight}.
     */
    private static class BalanceTarget {
        private static final BalanceTarget NONE = new BalanceTarget(0, -1);

        private final int height;
        private final int pageIdx;

        private BalanceTarget(int height, int pageIdx) {
            this.height = height;
            this.pageIdx = pageIdx;
        }

        /** The cap for a column on this page: none unless the page is the balanced fragment. */
        private int heightFor(int candidatePageIdx) {
            return candidatePageIdx == pageIdx ? height : 0;
        }
    }
    
    public static class ColumnBreakOpportunity {
        private final Box box;             // The box where we can break.
        private final List<Box> ancestors; // Ancestors of this box which should be moved with it.

        private ColumnBreakOpportunity(Box box, List<Box> ancestors) {
            this.box = box;
            this.ancestors = ancestors;
        }
        
        static ColumnBreakOpportunity of(Box box, List<Box> ancestors) {
            return new ColumnBreakOpportunity(box, ancestors);
        }
        
        @Override
        public String toString() {
            return String.valueOf(box);
        }
    }
    
    public static class ColumnBreakStore {
        // Break opportunity boxes.
        private final List<ColumnBreakOpportunity> breaks = new ArrayList<>();
        // Which container boxes have been processed, so we don't move them twice.
        private final Set<Box> processedContainers = new HashSet<>();
        
        /**
         * Add a break opportunity. If this is a break opportunity and a first child, it 
         * should also add all unprocessed ancestors, so they can be moved with the
         * first child.
         */
        public void addBreak(Box box, List<Box> ancestors) {
            breaks.add(ColumnBreakOpportunity.of(box, ancestors));
        }
        
        /**
         * Whether an ancestor box needs to be added to the list of ancestors.
         * @return true to process this ancestor (we haven't seen it yet).
         */
        public boolean checkContainerShouldProcess(Box container) {
            if (container instanceof FlowingColumnContainerBox ||
                container instanceof FlowingColumnBox) {
                return false;
            }
            
            return processedContainers.add(container);
        }

        @Override
        public String toString() {
            return breaks.toString();
        }
    }
    
    private void layoutFloats(TreeMap<Integer, ColumnPosition> columns, List<BoxOffset> floats, int columnCount, int colWidth, int colGap) {
        for (BoxOffset bo : floats) {
            BlockBox floater = bo.getBox();
            
            ColumnBreakStore store = new ColumnBreakStore();
            floater.findColumnBreakOpportunities(store);
            
            for (ColumnBreakOpportunity breakOp : store.breaks) {
                Map.Entry<Integer, ColumnPosition> entry = columns.floorEntry(breakOp.box.getAbsY());
                ColumnPosition column = entry.getValue();
            
                int yAdjust = column.pasteY - column.copyY;
                int xAdjust = ((column.columnIndex % columnCount) * colWidth) + ((column.columnIndex % columnCount) * colGap);

                reposition(breakOp.box, xAdjust, yAdjust);
                
                if (breakOp.ancestors != null) {
                    repositionAncestors(breakOp.ancestors, xAdjust, yAdjust);
                }
                
                refreshMovedBox(breakOp.box);
            }
        }
    }

    private void layoutFloats(TreeMap<Integer, ColumnPosition> columnMap, PersistentBFC bfc, int columnCount, int colWidth, int colGap) {
        List<BoxOffset> floatsL = this.getPersistentBFC().getFloatManager().getFloats(FloatManager.FloatDirection.LEFT);
        List<BoxOffset> floatsR = this.getPersistentBFC().getFloatManager().getFloats(FloatManager.FloatDirection.RIGHT);

        layoutFloats(columnMap, floatsL, columnCount, colWidth, colGap);
        layoutFloats(columnMap, floatsR, columnCount, colWidth, colGap);
    }
    
    private void reposition(Box box, int xAdjust, int yAdjust) {
        if (box instanceof BlockBox &&
            ((BlockBox) box).isFloated()) {
            box.setX(box.getX() + xAdjust);
            box.setY(box.getY() + yAdjust);
        } else {
            box.setAbsY(box.getAbsY() + yAdjust);
            box.setAbsX(box.getAbsX() + xAdjust);
        }
    }
    
    private void repositionAncestors(List<Box> ancestors, int xAdjust, int yAdjust) {
        for (Box ancestor : ancestors) {
            reposition(ancestor, xAdjust, yAdjust);
        }

        // FIXME: We do not resize or duplicate ancestor container boxes,
        // so if user has used border, background color
        // or overflow: hidden it will produce incorrect results.
    }

    private void refreshMovedBox(Box box) {
        box.calcChildLocations();
    }
    
    private int getMaxColumnHeight(PageBox page, int pasteY, int balancedHeight) {
        int pageHeight = page.getBottom() - pasteY;
        return balancedHeight > 0 ? Math.min(pageHeight, balancedHeight) : pageHeight;
    }

    private List<BreakMetrics> collectBreakMetrics(LayoutContext c, Box child) {
        ColumnBreakStore store = new ColumnBreakStore();
        child.findColumnBreakOpportunities(store);

        if (store.breaks.isEmpty() || store.breaks.size() == 1) {
            return Collections.emptyList();
        }

        Collections.sort(store.breaks,
                Comparator.comparingInt(brk -> brk.box.getAbsY() + brk.box.getBorderBoxHeight(c)));

        List<BreakMetrics> metrics = new ArrayList<>(store.breaks.size());
        for (ColumnBreakOpportunity brk : store.breaks) {
            metrics.add(new BreakMetrics(brk, c));
        }

        for (int i = 1; i < metrics.size(); i++) {
            metrics.get(i).avoidBreakBefore =
                    isBreakAvoided(metrics.get(i - 1).breakOpportunity.box, metrics.get(i).breakOpportunity.box);
        }

        return metrics;
    }

    /**
     * Whether the break between two adjacent break opportunities is to be avoided.
     *
     * <p>The break opportunities are leaves (line boxes and empty blocks), but break-after and
     * break-before are set on the blocks around them. Per css-break-3 section 4.1 (and the
     * propagation rule of section 3.1), the break point between two siblings is also the break
     * point after the last child of the first and before the first child of the second, at any
     * depth, and the values of all of those boxes apply to it. So the break between {@code before}
     * and {@code after} is avoided if {@code break-after: avoid} (or {@code avoid-column}, or
     * {@code page-break-after: avoid}) is set on {@code before}'s block or on any ancestor that
     * {@code before} ends, up to the nearest common ancestor; and the same for break-before on
     * {@code after}'s side. A heading that is the only child of a wrapper is thereby kept with
     * the first line of whatever follows the wrapper.
     *
     * <p>Line boxes carry no break properties of their own and are skipped. Only the last line of a
     * block ends that block, so break-after never affects the break between two lines of the same
     * block; {@code break-inside: avoid} (or {@code avoid-column}) on a box that contains both sides
     * does, so a heading of two lines is not split either.
     */
    private static boolean isBreakAvoided(Box before, Box after) {
        Map<Box, Boolean> afterChain = new IdentityHashMap<>();
        for (Box x = after; x != null; x = x.getParent()) {
            afterChain.put(x, Boolean.TRUE);
        }

        Box common = before;
        while (common != null && !afterChain.containsKey(common)) {
            common = common.getParent();
        }

        for (Box x = before; x != null && x != common; x = x.getParent()) {
            if (!(x instanceof LineBox) && x.getStyle() != null && x.getStyle().isAvoidColumnBreakAfter()) {
                return true;
            }
            if (!x.isLastChild()) {
                break;
            }
        }

        for (Box x = after; x != null && x != common; x = x.getParent()) {
            if (!(x instanceof LineBox) && x.getStyle() != null && x.getStyle().isAvoidColumnBreakBefore()) {
                return true;
            }
            if (!x.isFirstChild()) {
                break;
            }
        }

        // The break lies inside every box that contains both sides, up to the multi-column box.
        for (Box x = common; x != null && !(x instanceof FlowingColumnBox); x = x.getParent()) {
            if (!(x instanceof LineBox) && x.getStyle() != null && x.getStyle().isAvoidColumnBreakInside()) {
                return true;
            }
        }

        return false;
    }

    private static boolean isForcedBreak(BreakMetrics br, BreakMetrics next) {
        return br.breakOpportunity.box.getStyle().isColumnBreakAfter() ||
               next.breakOpportunity.box.getStyle().isColumnBreakBefore();
    }

    /**
     * Whether the column ends after break opportunity {@code i}, so that {@code i + 1} starts the
     * next column.
     *
     * <p>A forced break always ends it. Otherwise the column ends where the content no longer
     * fits, but never between two opportunities whose break is to be avoided: the opportunities
     * linked that way go to a column together, so the test before {@code i + 1} is whether the
     * whole group starting there still fits. Avoiding a break is a preference and never makes
     * content overflow: a group taller than the column is not moved as a unit, and an avoided break
     * is still taken when the next opportunity itself does not fit.
     */
    private static boolean endsColumnAfter(List<BreakMetrics> breaks, int i, int yAdjust, int pasteY, int maxColHeight) {
        BreakMetrics br = breaks.get(i);
        BreakMetrics next = breaks.get(i + 1);

        if (isForcedBreak(br, next)) {
            return true;
        }

        int nextHeight = next.bottom() + yAdjust - pasteY;
        if (next.avoidBreakBefore || nextHeight > maxColHeight) {
            return nextHeight > maxColHeight;
        }

        int last = i + 1;
        while (last + 1 < breaks.size() &&
               breaks.get(last + 1).avoidBreakBefore &&
               !isForcedBreak(breaks.get(last), breaks.get(last + 1))) {
            last++;
        }

        BreakMetrics groupEnd = breaks.get(last);
        if (groupEnd.bottom() - next.absY > maxColHeight) {
            return false;
        }

        return groupEnd.bottom() + yAdjust - pasteY > maxColHeight;
    }

    private LayoutRun simulateColumnLayout(LayoutContext c, List<BreakMetrics> breaks, int columnCount, BalanceTarget balance, boolean allowPageAdd) {
        final int startY = this.getAbsY();
        final List<PageBox> pages = c.getRootLayer().getPages();
        int pageIdx = findPageIndex(pages, startY);
        int colIdx = 0;
        int copyY = startY;
        int pasteY = startY;
        int maxColHeight = getMaxColumnHeight(pages.get(pageIdx), pasteY, balance.heightFor(pageIdx));
        int finalHeight = 0;

        for (int i = 0; i < breaks.size(); i++) {
            BreakMetrics br = breaks.get(i);
            BreakMetrics next = i < breaks.size() - 1 ? breaks.get(i + 1) : null;

            int yAdjust = pasteY - copyY;
            int yProposedFinal = br.absY + yAdjust;
            finalHeight = Math.max((yProposedFinal + br.borderBoxHeight) - startY, finalHeight);

            if (next != null) {
                if (endsColumnAfter(breaks, i, yAdjust, pasteY, maxColHeight)) {
                    int newColIdx = colIdx + 1;
                    boolean needNewPage = newColIdx % columnCount == 0;
                    int newPageIdx = needNewPage ? pageIdx + 1 : pageIdx;

                    if (newPageIdx >= pages.size()) {
                        if (!allowPageAdd) {
                            return new LayoutRun(finalHeight, newColIdx + 1, newPageIdx);
                        }
                        c.getRootLayer().addPage(c);
                    }

                    PageBox page = pages.get(newPageIdx);
                    copyY = next.absY;
                    pasteY = needNewPage ? page.getTop() : pasteY;
                    pageIdx = newPageIdx;
                    colIdx = newColIdx;
                    maxColHeight = getMaxColumnHeight(page, pasteY, balance.heightFor(pageIdx));
                }
            }
        }

        return new LayoutRun(finalHeight, colIdx + 1, pageIdx);
    }

    /**
     * Finds the height to cap the balanced columns to, and the fragment they are in.
     *
     * <p>CSS Multi-column Layout balances the LAST fragment only: "column boxes in the last
     * fragment are balanced, and all other fragments are filled" (css-multicol-1 section 3.3).
     * So the cap is searched for, and afterwards applied, on the page the content ends on and
     * on no other -- every earlier page fills to the page box exactly as column-fill: auto does.
     *
     * <p>A single cap shared by every page cannot express this. Where the flow runs over more
     * than one page the earlier pages are full by construction, so the smallest cap that still
     * fits the content in the same number of columns is the page height itself: the search
     * returns a no-op, the last page keeps whatever is left of the flow in its first column,
     * and the second column stays empty. That was the observed behaviour on a three-page
     * two-column document -- 78 lines in the left column of the last page and one in the
     * right, where the last fragment should have held about 40 and 39.
     */
    private BalanceTarget findBalancedColumnHeight(LayoutContext c, Box child, List<BreakMetrics> breaks, int columnCount) {
        // Where the content ends when every column is filled is where the last fragment is,
        // and filling is what every fragment before it does, so this run is also the layout
        // of all of them.
        LayoutRun baseline = simulateColumnLayout(c, breaks, columnCount, BalanceTarget.NONE, true);
        int balancePageIdx = baseline.lastPageIdx;

        // Nothing to balance: a single break opportunity cannot be divided between columns.
        if (breaks.size() <= 1) {
            return BalanceTarget.NONE;
        }

        // A column can never be shorter than the tallest thing that has to fit in one.
        int low = 1;
        for (BreakMetrics br : breaks) {
            low = Math.max(low, br.borderBoxHeight);
        }

        // The test is that the content still ENDS on the page it ended on when filled -- not
        // that it still occupies the same number of columns. Balancing the last fragment uses
        // more columns than filling it by definition: filling leaves the content in the first
        // column of that page and balancing spreads it over all of them. Asking for the same
        // column count therefore rejects every candidate and returns the full page height,
        // which is how this search used to come back with no balancing at all.
        int high = Math.max(low, child.getHeight());
        while (low < high) {
            int mid = low + ((high - low) / 2);
            LayoutRun attempt = simulateColumnLayout(
                    c, breaks, columnCount, new BalanceTarget(mid, balancePageIdx), false);
            if (attempt.lastPageIdx <= balancePageIdx) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }

        return new BalanceTarget(high, balancePageIdx);
    }

    private int adjustColumns(LayoutContext c, Box child, int colGap, int colWidth, int columnCount) {
        final int startY = this.getAbsY();
        final List<PageBox> pages = c.getRootLayer().getPages();
        final boolean haveFloats =
                !this.getPersistentBFC().getFloatManager().getFloats(FloatManager.FloatDirection.LEFT).isEmpty() ||
                !this.getPersistentBFC().getFloatManager().getFloats(FloatManager.FloatDirection.RIGHT).isEmpty();
        final TreeMap<Integer, ColumnPosition> columnMap = haveFloats ? new TreeMap<>() : null;

        List<BreakMetrics> breaks = collectBreakMetrics(c, child);
        if (breaks.isEmpty()) {
            return this.getChild().getHeight();
        }

        BalanceTarget balance = getStyle().isColumnFillAuto()
                ? BalanceTarget.NONE
                : findBalancedColumnHeight(c, child, breaks, columnCount);
        int startPageIdx = findPageIndex(pages, startY);
        int firstColumnHeight = getMaxColumnHeight(
                pages.get(startPageIdx), this.getChild().getAbsY(), balance.heightFor(startPageIdx));

        if (child.getHeight() <= firstColumnHeight && getStyle().isColumnFillAuto()) {
            return child.getHeight();
        }

        int pageIdx = startPageIdx;
        int colIdx = 0;
        int finalHeight = 0;
        ColumnPosition current = new ColumnPosition(
                colIdx,
                startY,
                startY,
                getMaxColumnHeight(pages.get(pageIdx), startY, balance.heightFor(pageIdx)),
                pageIdx);

        if (haveFloats) {
            columnMap.put(startY, current);
        }

        for (int i = 0; i < breaks.size(); i++) {
            BreakMetrics br = breaks.get(i);
            BreakMetrics next = i < breaks.size() - 1 ? breaks.get(i + 1) : null;
            ColumnBreakOpportunity breakOp = br.breakOpportunity;
            Box ch = breakOp.box;

            int yAdjust = current.pasteY - current.copyY;
            int yProposedFinal = br.absY + yAdjust;
            ch.setAbsY(yProposedFinal);

            finalHeight = Math.max((yProposedFinal + br.borderBoxHeight) - startY, finalHeight);

            int xAdjust = ((colIdx % columnCount) * colWidth) + ((colIdx % columnCount) * colGap);
            ch.setAbsX(ch.getAbsX() + xAdjust);

            if (breakOp.ancestors != null) {
                repositionAncestors(breakOp.ancestors, xAdjust, yAdjust);
            }

            refreshMovedBox(ch);

            if (next != null) {
                if (endsColumnAfter(breaks, i, yAdjust, current.pasteY, current.maxColHeight)) {
                    int newColIdx = colIdx + 1;
                    boolean needNewPage = newColIdx % columnCount == 0;
                    int newPageIdx = needNewPage ? current.pageIdx + 1 : current.pageIdx;

                    if (newPageIdx >= pages.size()) {
                        c.getRootLayer().addPage(c);
                    }

                    PageBox page = pages.get(newPageIdx);
                    int pasteY = needNewPage ? page.getTop() : current.pasteY;
                    int copyY = next.absY;

                    current = new ColumnPosition(
                            newColIdx,
                            copyY,
                            pasteY,
                            getMaxColumnHeight(page, pasteY, balance.heightFor(newPageIdx)),
                            newPageIdx);
                    if (haveFloats) {
                        columnMap.put(copyY, current);
                    }
                    colIdx = newColIdx;
                }
            }
        }

        if (haveFloats) {
            layoutFloats(columnMap, this.getPersistentBFC(), columnCount, colWidth, colGap);
        }

        return finalHeight;
    }

    @Override
    public void layout(LayoutContext c, int contentStart) {
        BlockFormattingContext bfc = new BlockFormattingContext(this, c);
        c.pushBFC(bfc);
        
        addBoxID(c);
        
        this.calcDimensions(c);

        int colCount = getStyle().columnCount();
        int colGapCount = colCount - 1;

        float colGap = getStyle().isIdent(CSSName.COLUMN_GAP, IdentValue.NORMAL) ? getStyle().getLineHeight(c)
                : /* Use the line height as a normal column gap. */
                getStyle().getFloatPropertyProportionalWidth(CSSName.COLUMN_GAP, getContentWidth(), c);

        float totalGap = colGap * colGapCount;
        int colWidth = (int) ((this.getContentWidth() - totalGap) / colCount);

        _child.setContainingLayer(this.getContainingLayer());
        _child.setContentWidth(colWidth);
        _child.setColumnWidth(colWidth);
        _child.setAbsX(this.getAbsX());
        _child.setAbsY(this.getAbsY());

        c.setIsPrintOverride(false);
        _child.layout(c, contentStart);
        c.setIsPrintOverride(null);

        int height = adjustColumns(c, _child, (int) colGap, colWidth, colCount);
        _child.setHeight(0);
        this.setHeight(height);
        c.popBFC();
    }

    public void setOnlyChild(LayoutContext c, FlowingColumnBox child) {
        this._child = child;
        this.addChild(child);
    }

    public FlowingColumnBox getChild() {
        return _child;
    }
}
