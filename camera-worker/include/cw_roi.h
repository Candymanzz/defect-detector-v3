#ifndef CW_ROI_H
#define CW_ROI_H

typedef struct {
    int min, max, inc;
} cw_roi_range;

typedef struct {
    int x, y, width, height;
} cw_roi_rect;

/* Validate without rounding: silently moving a crop would invalidate references. */
static inline int cw_roi_value_valid(int value, cw_roi_range range) {
    return range.inc > 0 && value >= range.min && value <= range.max
            && (value - range.min) % range.inc == 0;
}

static inline int cw_roi_valid(cw_roi_rect roi, int sensor_width, int sensor_height,
                               cw_roi_range x, cw_roi_range y,
                               cw_roi_range width, cw_roi_range height) {
    return roi.width > 0 && roi.height > 0
            && cw_roi_value_valid(roi.x, x) && cw_roi_value_valid(roi.y, y)
            && cw_roi_value_valid(roi.width, width) && cw_roi_value_valid(roi.height, height)
            && roi.width <= sensor_width && roi.x <= sensor_width - roi.width
            && roi.height <= sensor_height && roi.y <= sensor_height - roi.height;
}
#endif
