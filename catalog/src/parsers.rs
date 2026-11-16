//! 速读谷页面解析：列表页（/zuixin/N.html）与详情页（/{id}/）。
//!
//! 选择器集中在这个文件，站点改版时只改这里，并用 `fixtures/` 下的真实页面做单测。

use scraper::{Html, Selector};

#[derive(Debug, Clone)]
pub struct ListItem {
    pub id: i64,
    pub title: String,
    pub status: String,
    pub category: String,
    pub author: String,
    pub latest_chapter_id: i64,
    pub latest_chapter: String,
}

#[derive(Debug, Clone)]
pub struct Detail {
    pub id: i64,
    pub title: String,
    pub status: String,
    pub category: String,
    pub author: String,
    pub words_text: String,
    pub words: i64,
    pub last_update_at: String,
    pub latest_chapter_id: i64,
    pub latest_chapter: String,
    pub chapter_no: i64,
}

/// `/7407/` 或 `/7407/12345.html` → (7407, Some(12345))
fn parse_book_href(href: &str) -> Option<(i64, Option<i64>)> {
    let path = href
        .trim_start_matches("https://www.suduguu.com")
        .trim_start_matches("http://www.suduguu.com");
    let mut parts = path.trim_start_matches('/').split('/');
    let id: i64 = parts.next()?.parse().ok()?;
    match parts.next() {
        None | Some("") => Some((id, None)),
        Some(rest) => {
            let n = rest.strip_suffix(".html")?.parse().ok()?;
            Some((id, Some(n)))
        }
    }
}

fn text_of(el: &scraper::ElementRef<'_>) -> String {
    el.text().collect::<String>().trim().to_string()
}

/// 列表页：10 本/页，按更新时间倒序。
pub fn parse_list(html: &str) -> Vec<ListItem> {
    let doc = Html::parse_document(html);
    let item_sel = Selector::parse("div.item").unwrap();
    let h3a = Selector::parse("h3 a").unwrap();
    let span = Selector::parse("p span").unwrap();
    let author_a = Selector::parse("p a").unwrap();
    let chap_a = Selector::parse("ul li a").unwrap();

    let mut out = Vec::new();
    for item in doc.select(&item_sel) {
        let Some(title_el) = item.select(&h3a).next() else {
            continue;
        };
        let Some((id, _)) = parse_book_href(title_el.value().attr("href").unwrap_or("")) else {
            continue;
        };

        let spans: Vec<String> = item.select(&span).map(|s| text_of(&s)).collect();
        let status = spans.first().cloned().unwrap_or_default();
        let category = spans.get(1).cloned().unwrap_or_default();

        let author = item
            .select(&author_a)
            .map(|a| text_of(&a))
            .find(|t| t.starts_with("作者"))
            .map(|t| {
                t.trim_start_matches("作者：")
                    .trim_start_matches("作者:")
                    .to_string()
            })
            .unwrap_or_default();

        // 最新三章节里的第一个就是最新章节；取章号最大的那个更稳。
        let mut latest = (0i64, String::new());
        for a in item.select(&chap_a) {
            let href = a.value().attr("href").unwrap_or("");
            if let Some((bid, Some(cid))) = parse_book_href(href) {
                if bid == id && cid > latest.0 {
                    latest = (cid, text_of(&a));
                }
            }
        }

        out.push(ListItem {
            id,
            title: text_of(&title_el),
            status,
            category,
            author,
            latest_chapter_id: latest.0,
            latest_chapter: latest.1,
        });
    }
    out
}

/// 详情页：字数、状态、分类、作者、绝对更新时间、最新章节。
pub fn parse_detail(html: &str, id: i64) -> Option<Detail> {
    let doc = Html::parse_document(html);
    let h1 = Selector::parse("h1").unwrap();
    let h1_i = Selector::parse("h1 i").unwrap();
    let h1_a = Selector::parse("h1 a").unwrap();
    let author_a = Selector::parse("p a").unwrap();
    let dir_span = Selector::parse("h2#dir span").unwrap();
    let chap_a = Selector::parse("a[href]").unwrap();

    let h1_el = doc.select(&h1).next()?;
    let title = h1_el
        .select(&h1_a)
        .next()
        .map(|a| text_of(&a))
        .unwrap_or_default();
    let words_text = h1_el
        .select(&h1_i)
        .next()
        .map(|i| text_of(&i))
        .unwrap_or_default();

    // 分类名不统一：有的是「都市小说」，有的是「现代言情」「诸天无限」。
    // 只取 h1 后面那一段信息里的两个 span，别被导航里的 span 干扰。
    let info_sel = Selector::parse("div.itemtxt p span").unwrap();
    let info: Vec<String> = doc.select(&info_sel).map(|s| text_of(&s)).collect();
    let status = info
        .iter()
        .find(|s| *s == "连载中" || *s == "已完结" || *s == "已下架")
        .cloned()
        .unwrap_or_default();
    let category = info
        .iter()
        .find(|s| *s != &status && !s.starts_with("作者") && !s.is_empty())
        .cloned()
        .unwrap_or_default();

    let author = doc
        .select(&author_a)
        .map(|a| text_of(&a))
        .find(|t| t.starts_with("作者"))
        .map(|t| {
            t.trim_start_matches("作者：")
                .trim_start_matches("作者:")
                .to_string()
        })
        .unwrap_or_default();

    let last_update_at = doc
        .select(&dir_span)
        .next()
        .map(|s| text_of(&s))
        .unwrap_or_default()
        .trim_start_matches("更新时间：")
        .trim_start_matches("更新时间:")
        .trim()
        .to_string();

    // 目录最多列 999 条，但「最新三章节」里一定有最大的章号。
    let mut latest = (0i64, String::new());
    for a in doc.select(&chap_a) {
        let href = a.value().attr("href").unwrap_or("");
        if let Some((bid, Some(cid))) = parse_book_href(href) {
            if bid == id && cid > latest.0 {
                latest = (cid, text_of(&a));
            }
        }
    }

    Some(Detail {
        id,
        title,
        status,
        category,
        author,
        words: parse_words(&words_text),
        words_text,
        last_update_at,
        latest_chapter_id: latest.0,
        latest_chapter: latest.1.clone(),
        chapter_no: chapter_number(&latest.1),
    })
}

/// "653.5万字" → 6_535_000
pub fn parse_words(text: &str) -> i64 {
    let t = text.trim().trim_end_matches('字').trim_end_matches('万');
    let digits: String = t
        .chars()
        .filter(|c| c.is_ascii_digit() || *c == '.')
        .collect();
    digits
        .parse::<f64>()
        .map(|v| (v * 10_000.0).round() as i64)
        .unwrap_or(0)
}

/// 从「第4953章 …」「第三千一百六十二章 …」里取章号。
pub fn chapter_number(title: &str) -> i64 {
    let body = match (title.find('第'), title.find('章')) {
        (Some(a), Some(b)) if b > a + 1 => &title[a + 3..b],
        _ => return 0,
    };
    if let Ok(n) = body.parse::<i64>() {
        return n;
    }
    chinese_number(body).unwrap_or(0)
}

/// 简体中文数字（支持到万位），足够覆盖「第三千一百六十二章」。
fn chinese_number(s: &str) -> Option<i64> {
    const DIGITS: &[(char, i64)] = &[
        ('零', 0),
        ('〇', 0),
        ('一', 1),
        ('二', 2),
        ('两', 2),
        ('三', 3),
        ('四', 4),
        ('五', 5),
        ('六', 6),
        ('七', 7),
        ('八', 8),
        ('九', 9),
    ];
    let mut total = 0i64;
    let mut section = 0i64;
    let mut digit = 0i64;
    let mut seen = false;
    for ch in s.chars() {
        if let Some((_, d)) = DIGITS.iter().find(|(c, _)| *c == ch) {
            digit = *d;
            seen = true;
            continue;
        }
        let unit = match ch {
            '十' => 10,
            '百' => 100,
            '千' => 1_000,
            '万' => 10_000,
            _ => return None,
        };
        if unit == 10_000 {
            section += digit;
            total += section * 10_000;
            section = 0;
            digit = 0;
        } else {
            let d = if digit == 0 { 1 } else { digit };
            section += d * unit;
            digit = 0;
        }
        seen = true;
    }
    if !seen {
        return None;
    }
    Some(total + section + digit)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn words_parse() {
        assert_eq!(parse_words("653.5万字"), 6_535_000);
        assert_eq!(parse_words("1008.3万字"), 10_083_000);
        assert_eq!(parse_words("68.7万字"), 687_000);
        assert_eq!(parse_words(""), 0);
    }

    #[test]
    fn chapter_numbers() {
        assert_eq!(chapter_number("第332章 一月金丹，多拉赞助"), 332);
        assert_eq!(chapter_number("第三千一百六十二章 皇天后土"), 3162);
        assert_eq!(chapter_number("第一百零五章 试炼"), 105);
        assert_eq!(chapter_number("第十章 开端"), 10);
        assert_eq!(chapter_number("完本感言"), 0);
    }

    #[test]
    fn list_page_fixture() {
        let html = include_str!("../fixtures/zuixin_p1.html");
        let items = parse_list(html);
        assert_eq!(items.len(), 10, "列表页应解析出 10 本");
        assert!(items.iter().all(|i| i.id > 0 && !i.title.is_empty()));
        assert!(items.iter().all(|i| i.latest_chapter_id > 0));
    }

    #[test]
    fn detail_page_fixture() {
        let html = include_str!("../fixtures/book_51.html");
        let d = parse_detail(html, 51).expect("应能解析详情页");
        assert_eq!(d.title, "捞尸人");
        assert_eq!(d.status, "已完结");
        assert_eq!(d.category, "都市小说");
        assert_eq!(d.author, "纯洁滴小龙");
        assert_eq!(d.words, 6_535_000);
        assert_eq!(d.last_update_at, "2026-09-03 06:31:42");
        assert!(d.latest_chapter_id > 0);
    }

    /// 分类不总是「××小说」：言情类是「现代言情」，诸天类是「诸天无限」。
    #[test]
    fn detail_page_keeps_non_suffix_categories() {
        let html = include_str!("../fixtures/book_7388.html");
        let d = parse_detail(html, 7388).expect("应能解析详情页");
        assert_eq!(d.status, "连载中");
        assert_eq!(d.category, "现代言情");
        assert_eq!(d.author, "九九月");
        assert!(d.words > 0);
    }
}
