package ng.yito.imefloatingswitcher.shizuku;

import ng.yito.imefloatingswitcher.model.ImeSnapshot;
import ng.yito.imefloatingswitcher.model.SwitchResult;

interface IPrivilegedImeService {
    void destroy() = 16777114;
    ImeSnapshot readImeSnapshot() = 1;
    SwitchResult setInputMethod(String imeId) = 2;
}
