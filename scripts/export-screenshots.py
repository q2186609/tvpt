"""把 .shots 里的调试截图整理成 docs/screenshots 下的分类截图。

PNG 原图 1920x1080、单张 1-2MB，直接入库会把仓库撑爆；统一转成 1280 宽的
JPEG（q=85，去掉 EXIF），一张 100KB 上下，肉眼看不差别。
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, '.shots')
OUT = os.path.join(ROOT, 'docs', 'screenshots')

GROUPS = {
    '01-features': [
        ('feat/01_home.png', '01-home-with-downloads-tab.jpg'),
        ('feat/04_rail_downloads.png', '02-downloads-list.jpg'),
        ('feat/05_focus_resume.png', '03-downloads-focus-actions.jpg'),
        ('feat/06_after_resume.png', '04-downloads-resumed.jpg'),
        ('feat/07_delete_confirm.png', '05-downloads-delete-confirm.jpg'),
        ('feat/12_more_card.png', '06-home-more-card.jpg'),
        ('feat/13_more_page.png', '07-more-page.jpg'),
        ('feat/14_more_loaded.png', '08-more-page-loaded.jpg'),
        ('feat/18_actions.png', '09-stream-actions.jpg'),
        ('feat/20_queued2.png', '10-queued-for-download.jpg'),
        ('feat/52.png', '11-subtitle-panel.jpg'),
        ('feat/54_zh.png', '12-subtitle-chinese-on.jpg'),
        ('feat/63_settings.png', '13-settings-image-cache.jpg'),
    ],
    '02-ui-review': [
        ('final/01_home.png', '01-home.jpg'),
        ('final/04_row2.png', '02-home-row-focus.jpg'),
        ('final/08_row6.png', '03-home-last-row.jpg'),
        ('final/09_search.png', '04-search.jpg'),
        ('final/10_detail_series.png', '05-detail-series.jpg'),
        ('final/11_streams.png', '06-stream-list.jpg'),
        ('final/14_detail_movie.png', '07-detail-movie.jpg'),
        ('final/16_osd.png', '08-player-osd.jpg'),
        ('final/13_player_error.png', '09-player-error.jpg'),
        ('final/20_settings_input.png', '10-settings-input.jpg'),
    ],
    '03-tv-device': [
        ('tv/t30_final_home.png', '01-home.jpg'),
        ('tv/t3_row.png', '02-home-focus.jpg'),
        ('tv/t5_streams.png', '03-stream-list.jpg'),
        ('tv/t21_start.png', '04-startup-backdrop.jpg'),
        ('tv/t23_start.png', '05-playback-4k-hevc.jpg'),
        ('tv/t11_osd.png', '06-player-osd.jpg'),
        ('tv/t25_library.png', '07-library-continue-watching.jpg'),
    ],
}


def main():
    total = 0
    for group, items in GROUPS.items():
        out_dir = os.path.join(OUT, group)
        os.makedirs(out_dir, exist_ok=True)
        for src_rel, dst_name in items:
            src = os.path.join(SRC, src_rel.replace('/', os.sep))
            if not os.path.exists(src):
                print('MISS', src_rel)
                continue
            im = Image.open(src).convert('RGB')
            if im.width > 1280:
                h = round(im.height * 1280 / im.width)
                im = im.resize((1280, h), Image.LANCZOS)
            dst = os.path.join(out_dir, dst_name)
            im.save(dst, 'JPEG', quality=85, optimize=True, progressive=True)
            total += os.path.getsize(dst)
            print('%-46s %6.0f KB' % (group + '/' + dst_name, os.path.getsize(dst) / 1024))
    print('total %.1f MB' % (total / 1048576))


if __name__ == '__main__':
    main()
