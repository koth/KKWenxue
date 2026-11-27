//! 来源适配器边界。
//!
//! 适配器只负责把来源页面转换成来源作用域的数据；数据库和 API 不应再
//! 通过一个全局 source_url 猜测当前来源。

use crate::parsers;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceErrorKind {
    Redirect,
    NotFound,
    EmptyResponse,
    InvalidBook,
    InvalidDirectory,
    DuplicateChapter,
    Parse,
    Transport,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceError {
    pub kind: SourceErrorKind,
    pub message: String,
}

impl SourceError {
    fn new(kind: SourceErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct ExternalListItem {
    pub external_book_id: String,
    pub title: String,
    pub author: String,
    pub category: String,
    pub status: String,
    pub latest_external_chapter_id: Option<String>,
    pub latest_chapter: String,
}

#[derive(Debug, Clone)]
pub struct ExternalChapter {
    pub external_chapter_id: String,
    pub ordinal: usize,
    pub title: String,
}

#[derive(Debug, Clone)]
pub struct ExternalDetail {
    pub external_book_id: String,
    pub title: String,
    pub author: String,
    pub category: String,
    pub status: String,
    pub words_text: String,
    pub words: i64,
    pub last_update_at: String,
    pub latest_external_chapter_id: Option<String>,
    pub latest_chapter: String,
    pub chapter_no: i64,
    pub chapters: Vec<ExternalChapter>,
}

pub trait SourceAdapter: Send + Sync {
    fn source_key(&self) -> &'static str;
    fn display_name(&self) -> &'static str;
    fn base_url(&self) -> &'static str;
    fn adapter_version(&self) -> &'static str {
        "1"
    }

    fn list_path(&self, page: usize) -> String;
    fn detail_path(&self, external_book_id: &str) -> Result<String, SourceError>;
    fn chapter_path(
        &self,
        external_book_id: &str,
        external_chapter_id: &str,
        page: Option<usize>,
    ) -> Result<String, SourceError>;
    fn directory_page_path(
        &self,
        external_book_id: &str,
        page: usize,
    ) -> Result<String, SourceError>;

    fn parse_list(&self, html: &str) -> Result<Vec<ExternalListItem>, SourceError>;
    fn parse_detail(
        &self,
        html: &str,
        external_book_id: &str,
    ) -> Result<ExternalDetail, SourceError>;
    fn next_directory_page(&self, html: &str) -> Option<usize>;

    fn validate_response(
        &self,
        status: u16,
        final_url: &str,
        body: &str,
    ) -> Result<(), SourceError> {
        if (300..400).contains(&status) || final_url.contains("google.") {
            return Err(SourceError::new(
                SourceErrorKind::Redirect,
                "来源请求被重定向到未配置的站点",
            ));
        }
        if status == 404 {
            return Err(SourceError::new(
                SourceErrorKind::NotFound,
                "来源页面不存在",
            ));
        }
        if !(200..300).contains(&status) {
            return Err(SourceError::new(
                SourceErrorKind::Transport,
                format!("来源 HTTP {status}"),
            ));
        }
        if body.trim().is_empty() {
            return Err(SourceError::new(
                SourceErrorKind::EmptyResponse,
                "来源返回空页面",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy)]
pub struct SuduguuAdapter;

#[derive(Debug, Clone, Copy)]
pub struct Sudugu520Adapter;

fn convert_detail(detail: parsers::Detail) -> ExternalDetail {
    ExternalDetail {
        external_book_id: detail.id.to_string(),
        title: detail.title,
        author: detail.author,
        category: detail.category,
        status: detail.status,
        words_text: detail.words_text,
        words: detail.words,
        last_update_at: detail.last_update_at,
        latest_external_chapter_id: (detail.latest_chapter_id > 0)
            .then(|| detail.latest_chapter_id.to_string()),
        latest_chapter: detail.latest_chapter,
        chapter_no: detail.chapter_no,
        chapters: detail
            .chapters
            .into_iter()
            .enumerate()
            .map(|(ordinal, chapter)| ExternalChapter {
                external_chapter_id: chapter.source_id.to_string(),
                ordinal,
                title: chapter.title,
            })
            .collect(),
    }
}

fn parse_external_id(value: &str) -> Result<i64, SourceError> {
    value.parse::<i64>().map_err(|_| {
        SourceError::new(
            SourceErrorKind::InvalidBook,
            format!("外站书籍 ID 无效：{value}"),
        )
    })
}

macro_rules! impl_adapter {
    ($type:ty, $key:literal, $name:literal, $base:literal, $host:literal) => {
        impl SourceAdapter for $type {
            fn source_key(&self) -> &'static str {
                $key
            }

            fn display_name(&self) -> &'static str {
                $name
            }

            fn base_url(&self) -> &'static str {
                $base
            }

            fn list_path(&self, page: usize) -> String {
                if page <= 1 {
                    "/zuixin/".into()
                } else {
                    format!("/zuixin/{page}.html")
                }
            }

            fn detail_path(&self, external_book_id: &str) -> Result<String, SourceError> {
                let id = parse_external_id(external_book_id)?;
                Ok(format!("/{id}/"))
            }

            fn chapter_path(
                &self,
                external_book_id: &str,
                external_chapter_id: &str,
                page: Option<usize>,
            ) -> Result<String, SourceError> {
                let book = parse_external_id(external_book_id)?;
                let chapter = parse_external_id(external_chapter_id)?;
                Ok(match page {
                    Some(page) => format!("/{book}/{chapter}-{page}.html"),
                    None => format!("/{book}/{chapter}.html"),
                })
            }

            fn directory_page_path(
                &self,
                external_book_id: &str,
                page: usize,
            ) -> Result<String, SourceError> {
                let book = parse_external_id(external_book_id)?;
                if page == 0 {
                    return Err(SourceError::new(
                        SourceErrorKind::InvalidDirectory,
                        "目录页码无效",
                    ));
                }
                Ok(format!("/{book}/p-{page}.html"))
            }

            fn parse_list(&self, html: &str) -> Result<Vec<ExternalListItem>, SourceError> {
                let items = parsers::parse_list_for_host(html, $host);
                if items.is_empty() {
                    return Err(SourceError::new(
                        SourceErrorKind::Parse,
                        "列表页没有可识别书籍",
                    ));
                }
                Ok(items
                    .into_iter()
                    .map(|item| ExternalListItem {
                        external_book_id: item.id.to_string(),
                        title: item.title,
                        author: item.author,
                        category: item.category,
                        status: item.status,
                        latest_external_chapter_id: (item.latest_chapter_id > 0)
                            .then(|| item.latest_chapter_id.to_string()),
                        latest_chapter: item.latest_chapter,
                    })
                    .collect())
            }

            fn parse_detail(
                &self,
                html: &str,
                external_book_id: &str,
            ) -> Result<ExternalDetail, SourceError> {
                let id = parse_external_id(external_book_id)?;
                let detail = parsers::parse_detail_for_host(html, id, $host)
                    .ok_or_else(|| SourceError::new(SourceErrorKind::Parse, "详情页无法解析"))?;
                if detail.title.trim().is_empty()
                    || detail.author.trim().is_empty()
                    || detail.chapters.is_empty()
                {
                    return Err(SourceError::new(
                        SourceErrorKind::InvalidDirectory,
                        "详情页缺少书名、作者或目录",
                    ));
                }
                let converted = convert_detail(detail);
                let mut seen = std::collections::HashSet::new();
                if converted
                    .chapters
                    .iter()
                    .any(|chapter| !seen.insert(chapter.external_chapter_id.as_str()))
                {
                    return Err(SourceError::new(
                        SourceErrorKind::DuplicateChapter,
                        "目录包含重复章节 ID",
                    ));
                }
                Ok(converted)
            }

            fn next_directory_page(&self, html: &str) -> Option<usize> {
                parsers::next_directory_page(html)
            }
        }
    };
}

impl_adapter!(
    SuduguuAdapter,
    "suduguu",
    "速读谷",
    "https://www.suduguu.com",
    "www.suduguu.com"
);
impl_adapter!(
    Sudugu520Adapter,
    "sudugu520",
    "速读谷 520",
    "https://www.sudugu520.com",
    "www.sudugu520.com"
);

pub fn built_in_adapters() -> Vec<Box<dyn SourceAdapter>> {
    vec![Box::new(SuduguuAdapter), Box::new(Sudugu520Adapter)]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_ids_are_strings_and_are_scoped_by_adapter() {
        let a = SuduguuAdapter;
        let b = Sudugu520Adapter;
        assert_eq!(a.source_key(), "suduguu");
        assert_eq!(b.source_key(), "sudugu520");
        assert_eq!(a.detail_path("203").unwrap(), "/203/");
        assert_eq!(b.chapter_path("407", "88", None).unwrap(), "/407/88.html");
    }

    #[test]
    fn invalid_source_responses_are_rejected_before_parsing() {
        let adapter = Sudugu520Adapter;
        assert_eq!(
            adapter
                .validate_response(302, "https://www.google.com/", "<html/>")
                .unwrap_err()
                .kind,
            SourceErrorKind::Redirect
        );
        assert_eq!(
            adapter
                .validate_response(404, "https://www.sudugu520.com/407/", "not found")
                .unwrap_err()
                .kind,
            SourceErrorKind::NotFound
        );
    }

    #[test]
    fn sudugu520_407_fixture_keeps_relative_links_and_page_paths() {
        let html = include_str!("../fixtures/book_51.html")
            .replace("www.suduguu.com", "www.sudugu520.com")
            .replace("/51/", "/407/");
        let adapter = Sudugu520Adapter;
        let detail = adapter
            .parse_detail(&html, "407")
            .expect("sudugu520 /407/ detail");
        assert_eq!(detail.external_book_id, "407");
        assert!(!detail.chapters.is_empty());
        assert_eq!(
            adapter.directory_page_path("407", 2).unwrap(),
            "/407/p-2.html"
        );
        assert_eq!(
            adapter.chapter_path("407", "88", Some(2)).unwrap(),
            "/407/88-2.html"
        );
    }
}
