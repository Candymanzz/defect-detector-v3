#include "cw_roi.h"
#undef NDEBUG
#include <assert.h>

int main(void) {
    cw_roi_range offset = {0, 1024, 4}, width = {16, 1024, 8}, height = {8, 768, 2};
    assert(cw_roi_valid((cw_roi_rect){100, 20, 512, 400}, 1024, 768, offset, offset, width, height));
    assert(!cw_roi_valid((cw_roi_rect){101, 20, 512, 400}, 1024, 768, offset, offset, width, height));
    assert(!cw_roi_valid((cw_roi_rect){100, 20, 513, 400}, 1024, 768, offset, offset, width, height));
    assert(!cw_roi_valid((cw_roi_rect){1000, 20, 512, 400}, 1024, 768, offset, offset, width, height));
    assert(!cw_roi_valid((cw_roi_rect){0, 0, 0, 400}, 1024, 768, offset, offset, width, height));
    assert(!cw_roi_valid((cw_roi_rect){0, -4, 512, 400}, 1024, 768, offset, offset, width, height));
    assert(cw_roi_valid((cw_roi_rect){0, 0, 1024, 768}, 1024, 768, offset, offset, width, height));
    return 0;
}
