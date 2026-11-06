from pathlib import Path
import re
import shutil
import zipfile
from bs4 import BeautifulSoup, NavigableString
from PIL import Image
from docx import Document
from docx.enum.section import WD_ORIENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

root = Path(__file__).resolve().parents[1]
source = root / 'manual/index.html'
target = root / 'build/distributions/user-manual.docx'
soup = BeautifulSoup(source.read_text(encoding='utf-8'), 'html.parser')
version = soup.select_one('meta[name="manual-version"]')['content']
updated = soup.select_one('meta[name="manual-updated"]')['content']
for node in soup.select('[data-doc-version]'):
    node.string = version
for node in soup.select('[data-doc-date]'):
    node.string = updated

document = Document()
section = document.sections[0]
section.orientation = WD_ORIENT.LANDSCAPE
section.page_width, section.page_height = Cm(29.7), Cm(21)
section.top_margin, section.bottom_margin = Cm(2.0), Cm(1.2)
section.left_margin = section.right_margin = Cm(1.2)
section.header_distance, section.footer_distance = Cm(.3), Cm(.45)
width = 27.3

for name in ['Normal', 'Heading 1', 'Heading 2', 'Heading 3', 'Caption']:
    style = document.styles[name]
    style.font.name = 'Malgun Gothic'
    style.element.get_or_add_rPr().get_or_add_rFonts().set(qn('w:eastAsia'), 'Malgun Gothic')
    style.font.color.rgb = RGBColor.from_string('153447')
normal = document.styles['Normal']
normal.font.size = Pt(9.5)
normal.paragraph_format.space_after = Pt(4)
normal.paragraph_format.line_spacing = 1.2
for name, size in [('Heading 1', 18), ('Heading 2', 17), ('Heading 3', 10.5)]:
    document.styles[name].font.size = Pt(size)
    document.styles[name].paragraph_format.space_before = Pt(5)
    document.styles[name].paragraph_format.space_after = Pt(6)
document.styles['Caption'].font.size = Pt(7.5)
document.styles['Caption'].font.color.rgb = RGBColor.from_string('526675')

header = section.header.paragraphs[0]
header.alignment = WD_ALIGN_PARAGRAPH.RIGHT
header.paragraph_format.space_after = Pt(0)
header.add_run().add_picture(str(root / 'manual/assets/Kari_logo.png'), width=Cm(4.2))
footer = section.footer.paragraphs[0]
footer.alignment = WD_ALIGN_PARAGRAPH.RIGHT
run = footer.add_run('© 2026 imtplus · 사용자 설명서 ' + version + '     ')
run.font.size = Pt(8)
for field, suffix in [('PAGE', ' / '), ('NUMPAGES', '')]:
    run = footer.add_run()
    code = OxmlElement('w:fldSimple')
    code.set(qn('w:instr'), field)
    run._r.addnext(code)
    footer.add_run(suffix).font.size = Pt(8)

def paragraph(parent, style=None):
    if hasattr(parent, '_tc') and len(parent.paragraphs) == 1:
        first = parent.paragraphs[0]
        if not first.text and not first.runs:
            if style:
                first.style = style
            return first
    return parent.add_paragraph(style=style)

def inline(node, line):
    if isinstance(node, NavigableString):
        if str(node).strip():
            line.add_run(str(node))
        return
    if node.name == 'br':
        line.add_run().add_break()
    elif node.name in ['b', 'strong', 'code', 'small']:
        run = line.add_run(node.get_text())
        run.bold = node.name in ['b', 'strong']
        if node.name == 'code':
            run.font.name = 'Consolas'
        if node.name == 'small':
            run.font.size = Pt(8)
    else:
        for child in node.children:
            inline(child, line)

def border(cell, visible):
    props = cell._tc.get_or_add_tcPr()
    edges = OxmlElement('w:tcBorders')
    for edge in ['top', 'left', 'bottom', 'right']:
        value = OxmlElement('w:' + edge)
        value.set(qn('w:val'), 'single' if visible else 'nil')
        value.set(qn('w:sz'), '4')
        value.set(qn('w:color'), 'D4E1E8')
        edges.append(value)
    props.append(edges)

def table(parent, rows, fractions=None, visible=True):
    result = parent.add_table(rows=rows, cols=len(fractions)) if hasattr(parent, '_tc') else parent.add_table(rows=rows, cols=len(fractions))
    result.autofit = False
    return result

def render(node, parent=document, available=width):
    if isinstance(node, NavigableString):
        if str(node).strip():
            paragraph(parent).add_run(str(node).strip())
        return
    classes = node.get('class', [])
    if 'page-foot' in classes or 'sheet-head' in classes:
        return
    if node.name == 'figure':
        image = node.find('img')
        if image:
            image_path = root / 'manual' / image['src']
            with Image.open(image_path) as bitmap:
                iw, ih = bitmap.size
            picture_width = min(available, 6.6 * iw / ih)
            line = paragraph(parent)
            line.alignment = WD_ALIGN_PARAGRAPH.CENTER
            line.add_run().add_picture(str(image_path), width=Cm(picture_width))
        caption = node.find('figcaption')
        if caption:
            paragraph(parent, 'Caption').add_run(caption.get_text())
        return
    if node.name == 'img':
        if 'manual-kari' not in classes:
            paragraph(parent).add_run().add_picture(str(root / 'manual' / node['src']), width=Cm(2.6))
        return
    if node.get('id') == 'printed-toc':
        links = node.find_all('a')
        grid = parent.add_table(rows=(len(links)+1)//2, cols=2)
        grid.autofit = False
        for index, link in enumerate(links):
            cell = grid.cell(index//2, index%2)
            cell.width = Cm(available/2)
            border(cell, False)
            line = cell.paragraphs[0]
            line.paragraph_format.space_after = Pt(1)
            title = link.find('span').get_text()
            number = link.find('small').get_text()
            anchor = OxmlElement('w:hyperlink')
            anchor.set(qn('w:anchor'), link['href'][1:].replace('-', '_'))
            run = OxmlElement('w:r')
            props = OxmlElement('w:rPr')
            size = OxmlElement('w:sz'); size.set(qn('w:val'), '16'); props.append(size)
            run.append(props)
            text = OxmlElement('w:t'); text.text = number + ' · ' + title
            run.append(text); anchor.append(run); line._p.append(anchor)
        return
    if node.name == 'table':
        rows = node.find_all('tr')
        if not rows:
            return
        count = len(rows[0].find_all(['td', 'th'], recursive=False))
        fractions = {2: [.27, .73], 3: [.31, .25, .44], 4: [.2, .28, .3, .22]}.get(count, [1/count]*count)
        if 'JSON 키' in rows[0].get_text():
            fractions = [.5, .2, .3]
        grid = parent.add_table(rows=len(rows), cols=count)
        grid.autofit = False
        for i, row in enumerate(rows):
            cells = row.find_all(['td', 'th'], recursive=False)
            for j, source_cell in enumerate(cells):
                cell = grid.cell(i, j)
                cell.width = Cm(available * fractions[j])
                border(cell, True)
                line = cell.paragraphs[0]
                line.paragraph_format.space_after = Pt(2)
                line.paragraph_format.space_before = Pt(2)
                for child in source_cell.children:
                    inline(child, line)
                for run in line.runs:
                    run.font.size = Pt(8.3)
                    if i == 0 or j == 0:
                        run.bold = True
                if i == 0:
                    shade = OxmlElement('w:shd'); shade.set(qn('w:fill'), 'EDF4F7')
                    cell._tc.get_or_add_tcPr().append(shade)
            if i == 0:
                repeat = OxmlElement('w:tblHeader')
                grid.rows[i]._tr.get_or_add_trPr().append(repeat)
        paragraph(parent).paragraph_format.space_after = Pt(1)
        return
    if node.name == 'ol':
        for i, item in enumerate(node.find_all('li', recursive=False), 1):
            line = paragraph(parent)
            line.paragraph_format.left_indent = Cm(.45)
            line.paragraph_format.first_line_indent = Cm(-.45)
            line.add_run(str(i) + '. ').bold = True
            for child in item.children:
                inline(child, line)
        return
    if node.name in ['p', 'aside', 'pre', 'h2', 'h3']:
        style = {'h2': 'Heading 2', 'h3': 'Heading 3'}.get(node.name)
        line = paragraph(parent, style)
        for child in node.children:
            inline(child, line)
        if node.name == 'aside':
            line.paragraph_format.left_indent = Cm(.3)
            props = line._p.get_or_add_pPr()
            shade = OxmlElement('w:shd'); shade.set(qn('w:fill'), 'EDF5F7'); props.append(shade)
            for run in line.runs:
                run.font.size = Pt(8.5)
        if node.name == 'pre':
            for run in line.runs:
                run.font.name = 'Consolas'; run.font.size = Pt(8.5)
        return
    if 'split' in classes or 'three' in classes or 'flow' in classes or 'frame-bar' in classes:
        children = node.find_all(['div', 'span'], recursive=False)
        if children:
            grid = parent.add_table(rows=1, cols=len(children))
            grid.autofit = False
            for i, child in enumerate(children):
                cell = grid.cell(0, i)
                cell.width = Cm(available/len(children))
                border(cell, False)
                for content in child.children:
                    render(content, cell, available/len(children)-.3)
        return
    if 'formula' in classes:
        line = paragraph(parent)
        line.paragraph_format.space_after = Pt(8)
        for child in node.children:
            inline(child, line)
        for run in line.runs:
            run.font.size = Pt(12)
        return
    for child in node.children:
        render(child, parent, available)

pages = soup.select('.sheet')
for index, page in enumerate(pages):
    if index:
        document.add_page_break()
    head = paragraph(document)
    eyebrow = head.add_run(page.select_one('.eyebrow').get_text())
    eyebrow.font.size = Pt(8)
    eyebrow.font.color.rgb = RGBColor.from_string('006F72')
    title = document.add_paragraph(page.select_one('h1').get_text(), style='Heading 1')
    start = OxmlElement('w:bookmarkStart')
    start.set(qn('w:id'), str(index+1)); start.set(qn('w:name'), page['id'].replace('-', '_'))
    end = OxmlElement('w:bookmarkEnd'); end.set(qn('w:id'), str(index+1))
    title._p.insert(0, start); title._p.append(end)
    for child in page.children:
        render(child)

document.core_properties.title = soup.title.get_text()
document.core_properties.version = version
document.core_properties.author = 'imtplus'
document.core_properties.subject = '문구·표 편집용 · 실제 화면 캡처 포함'
document.core_properties.comments = 'HTML/PDF와 별도의 편집본입니다. Word 수정은 웹 설명서에 자동 반영되지 않습니다.'
target.parent.mkdir(parents=True, exist_ok=True)
document.save(target)
copy = root / 'manual/user-manual.docx'
shutil.copyfile(target, copy)

with zipfile.ZipFile(target) as package:
    assert package.testzip() is None
    xml = package.read('word/document.xml').decode('utf-8')
    assert xml.count('<w:bookmarkStart ') == len(pages)
    assert 'imtplus' in package.read('word/footer1.xml').decode('utf-8')
    assert '관측값' in xml and 'PDF' in xml
    assert len([name for name in package.namelist() if name.startswith('word/media/')]) >= 30
print('PASS: editable DOCX,', len(pages), 'sections with text, tables, screenshots, KARI header and page fields')
print(target)
