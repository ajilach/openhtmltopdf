package com.openhtmltopdf.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
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

        private BreakMetrics(ColumnBreakOpportunity breakOpportunity, LayoutContext c) {
            this.breakOpportunity = breakOpportunity;
            this.absY = breakOpportunity.box.getAbsY();
            this.borderBoxHeight = breakOpportunity.box.getBorderBoxHeight(c);
        }
    }

    private static class LayoutRun {
        private final int finalHeight;
        private final int columnsUsed;

        private LayoutRun(int finalHeight, int columnsUsed) {
            this.finalHeight = finalHeight;
            this.columnsUsed = columnsUsed;
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

        return metrics;
    }

    private LayoutRun simulateColumnLayout(LayoutContext c, List<BreakMetrics> breaks, int columnCount, int balancedHeight, boolean allowPageAdd) {
        final int startY = this.getAbsY();
        final List<PageBox> pages = c.getRootLayer().getPages();
        int pageIdx = findPageIndex(pages, startY);
        int colIdx = 0;
        int copyY = startY;
        int pasteY = startY;
        int maxColHeight = getMaxColumnHeight(pages.get(pageIdx), pasteY, balancedHeight);
        int finalHeight = 0;

        for (int i = 0; i < breaks.size(); i++) {
            BreakMetrics br = breaks.get(i);
            BreakMetrics next = i < breaks.size() - 1 ? breaks.get(i + 1) : null;

            int yAdjust = pasteY - copyY;
            int yProposedFinal = br.absY + yAdjust;
            finalHeight = Math.max((yProposedFinal + br.borderBoxHeight) - startY, finalHeight);

            if (next != null) {
                int nextYHeight = next.absY + yAdjust + next.borderBoxHeight - pasteY;

                if (nextYHeight > maxColHeight ||
                    br.breakOpportunity.box.getStyle().isColumnBreakAfter() ||
                    next.breakOpportunity.box.getStyle().isColumnBreakBefore()) {
                    int newColIdx = colIdx + 1;
                    boolean needNewPage = newColIdx % columnCount == 0;
                    int newPageIdx = needNewPage ? pageIdx + 1 : pageIdx;

                    if (newPageIdx >= pages.size()) {
                        if (!allowPageAdd) {
                            return new LayoutRun(finalHeight, newColIdx + 1);
                        }
                        c.getRootLayer().addPage(c);
                    }

                    PageBox page = pages.get(newPageIdx);
                    copyY = next.absY;
                    pasteY = needNewPage ? page.getTop() : pasteY;
                    pageIdx = newPageIdx;
                    colIdx = newColIdx;
                    maxColHeight = getMaxColumnHeight(page, pasteY, balancedHeight);
                }
            }
        }

        return new LayoutRun(finalHeight, colIdx + 1);
    }

    private int findBalancedColumnHeight(LayoutContext c, Box child, List<BreakMetrics> breaks, int columnCount) {
        LayoutRun baseline = simulateColumnLayout(c, breaks, columnCount, 0, true);
        int desiredColumns = baseline.columnsUsed;

        // If the content fits in one full-height column, still split it across the
        // available columns when column-fill: balance is requested.
        if (desiredColumns <= 1) {
            desiredColumns = Math.min(columnCount, breaks.size());
        }

        if (desiredColumns <= 1) {
            return 0;
        }

        int low = 1;
        for (BreakMetrics br : breaks) {
            low = Math.max(low, br.borderBoxHeight);
        }

        int high = Math.max(low, child.getHeight());
        while (low < high) {
            int mid = low + ((high - low) / 2);
            LayoutRun attempt = simulateColumnLayout(c, breaks, columnCount, mid, false);
            if (attempt.columnsUsed <= desiredColumns) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }

        return high;
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

        int balancedHeight = getStyle().isColumnFillAuto() ? 0 : findBalancedColumnHeight(c, child, breaks, columnCount);
        int firstColumnHeight = getMaxColumnHeight(pages.get(findPageIndex(pages, startY)), this.getChild().getAbsY(), balancedHeight);

        if (child.getHeight() <= firstColumnHeight && getStyle().isColumnFillAuto()) {
            return child.getHeight();
        }

        int pageIdx = findPageIndex(pages, startY);
        int colIdx = 0;
        int finalHeight = 0;
        ColumnPosition current = new ColumnPosition(
                colIdx,
                startY,
                startY,
                getMaxColumnHeight(pages.get(pageIdx), startY, balancedHeight),
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
                int nextYHeight = next.absY + yAdjust + next.borderBoxHeight - current.pasteY;

                if (nextYHeight > current.maxColHeight ||
                    ch.getStyle().isColumnBreakAfter() ||
                    next.breakOpportunity.box.getStyle().isColumnBreakBefore()) {
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
                            getMaxColumnHeight(page, pasteY, balancedHeight),
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
