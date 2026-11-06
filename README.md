Print https://www.jetbrains.com/idea/whatsnew/2026-3/

```
document.querySelectorAll('div.page__header, div.page__footer, section#start-now, div.whatsnew-container.whatsnew-previous-release-block, aside, div.whatsnew-container-sidebar > section, div.whatsnew-top-section__content > div:first-child, div.whatsnew-feature-block__button').forEach(e => e.parentElement.removeChild(e));
```
```
.whatsnew-container-sidebar {
  --sidebar-width: 0;
  width: auto;
  margin: 0;
}
```
