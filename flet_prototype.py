import flet as ft


IMAGE_ITEMS = [
    {
        "id": "img-01",
        "title": "Sunset Geometry",
        "color": "#E76F51",
        "tags": ["warm", "poster", "graphic"],
        "notes": "Strong shape language and bold evening tones.",
        "favorite": True,
    },
    {
        "id": "img-02",
        "title": "Seafoam Layout",
        "color": "#2A9D8F",
        "tags": ["editorial", "fresh", "coastal"],
        "notes": "Feels clean and spacious, useful for calm interfaces.",
        "favorite": False,
    },
    {
        "id": "img-03",
        "title": "Night Studio",
        "color": "#264653",
        "tags": ["dark", "portrait", "lighting"],
        "notes": "Great contrast reference for moody portrait work.",
        "favorite": True,
    },
    {
        "id": "img-04",
        "title": "Apricot Motion",
        "color": "#F4A261",
        "tags": ["fashion", "movement", "soft"],
        "notes": "Good reference for dynamic compositions.",
        "favorite": False,
    },
    {
        "id": "img-05",
        "title": "Canvas Bloom",
        "color": "#8AB17D",
        "tags": ["organic", "illustration", "green"],
        "notes": "Nice natural palette for softer concepts.",
        "favorite": False,
    },
    {
        "id": "img-06",
        "title": "Signal Red",
        "color": "#C1121F",
        "tags": ["campaign", "bold", "attention"],
        "notes": "Would work well for punchy callout moments.",
        "favorite": True,
    },
]


def main(page: ft.Page) -> None:
    page.title = "Image Interest Tracker Prototype"
    page.window_width = 1400
    page.window_height = 920
    page.padding = 0
    page.spacing = 0
    page.bgcolor = "#F5EFE5"
    page.theme_mode = ft.ThemeMode.LIGHT
    page.fonts = {
        "Display": "https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@0;1&display=swap",
        "Body": "https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;700&display=swap",
    }
    page.theme = ft.Theme(font_family="Body")

    state = {
        "items": [dict(item) for item in IMAGE_ITEMS],
        "selected_id": IMAGE_ITEMS[0]["id"],
    }

    search_field = ft.TextField(
        hint_text="Search title, tags, or notes",
        border_radius=18,
        filled=True,
        bgcolor="#FFF9F2",
        border_color="#D8C7B7",
        content_padding=16,
        prefix_icon=ft.Icons.SEARCH,
        expand=True,
        on_change=lambda _: refresh_gallery(),
    )

    tag_filter = ft.Dropdown(
        width=220,
        border_radius=18,
        filled=True,
        bgcolor="#FFF9F2",
        border_color="#D8C7B7",
        hint_text="Filter by tag",
        options=[ft.dropdown.Option("All tags")],
        value="All tags",
        on_change=lambda _: refresh_gallery(),
    )

    favorites_only = ft.Switch(
        label="Favorites only",
        active_color="#B6532F",
        on_change=lambda _: refresh_gallery(),
    )

    title_input = ft.TextField(
        label="Title",
        border_radius=16,
        filled=True,
        bgcolor="#FFF9F2",
        border_color="#D8C7B7",
    )
    color_input = ft.TextField(
        label="Square color",
        value="#7B6CF6",
        border_radius=16,
        filled=True,
        bgcolor="#FFF9F2",
        border_color="#D8C7B7",
    )
    tags_input = ft.TextField(
        label="Tags (comma separated)",
        border_radius=16,
        filled=True,
        bgcolor="#FFF9F2",
        border_color="#D8C7B7",
    )
    notes_input = ft.TextField(
        label="Notes",
        multiline=True,
        min_lines=3,
        max_lines=5,
        border_radius=16,
        filled=True,
        bgcolor="#FFF9F2",
        border_color="#D8C7B7",
    )
    form_message = ft.Text("", color="#6B6259", size=13)

    gallery_grid = ft.ResponsiveRow(run_spacing=18, spacing=18)
    results_label = ft.Text(color="#6B6259", size=14)

    detail_title = ft.Text("", size=28, weight=ft.FontWeight.W_600, color="#1F1D1A")
    detail_square = ft.Container(
        width=320,
        height=240,
        border_radius=26,
        bgcolor="#E76F51",
        animate=ft.Animation(250, ft.AnimationCurve.EASE_OUT),
        content=ft.Stack(
            controls=[
                ft.Container(
                    left=20,
                    top=18,
                    content=ft.Text("IMAGE STAND-IN", color="#FFF8F4", size=13, weight=ft.FontWeight.W_700),
                ),
                ft.Container(
                    right=18,
                    bottom=18,
                    width=78,
                    height=78,
                    border_radius=18,
                    bgcolor="#FFFFFF22",
                ),
            ]
        ),
    )
    detail_tags = ft.Row(wrap=True, spacing=8)
    detail_notes = ft.Text("", size=15, color="#5E574F")
    detail_status = ft.Text("", size=14, color="#8A3C1D", italic=True)

    def available_tags() -> list[str]:
        tags = sorted({tag for item in state["items"] for tag in item["tags"]})
        return ["All tags", *tags]

    def normalize_color(value: str) -> str:
        cleaned = value.strip() or "#7B6CF6"
        if not cleaned.startswith("#"):
            cleaned = f"#{cleaned}"
        if len(cleaned) == 4:
            cleaned = "#" + "".join(ch * 2 for ch in cleaned[1:])
        if len(cleaned) != 7:
            return "#7B6CF6"
        try:
            int(cleaned[1:], 16)
            return cleaned.upper()
        except ValueError:
            return "#7B6CF6"

    def current_selection():
        selected_id = state["selected_id"]
        for item in state["items"]:
            if item["id"] == selected_id:
                return item
        return state["items"][0] if state["items"] else None

    def update_filter_options() -> None:
        current_value = tag_filter.value or "All tags"
        options = available_tags()
        tag_filter.options = [ft.dropdown.Option(tag) for tag in options]
        tag_filter.value = current_value if current_value in options else "All tags"

    def filtered_items() -> list[dict]:
        query = (search_field.value or "").strip().lower()
        selected_tag = tag_filter.value or "All tags"
        favorite_only = favorites_only.value

        matches = []
        for item in state["items"]:
            haystack = " ".join([item["title"], item["notes"], " ".join(item["tags"])]).lower()
            if query and query not in haystack:
                continue
            if selected_tag != "All tags" and selected_tag not in item["tags"]:
                continue
            if favorite_only and not item["favorite"]:
                continue
            matches.append(item)
        return matches

    def select_item(item_id: str) -> None:
        state["selected_id"] = item_id
        render_detail()
        refresh_gallery()

    def toggle_favorite(item_id: str) -> None:
        for item in state["items"]:
            if item["id"] == item_id:
                item["favorite"] = not item["favorite"]
                detail_status.value = f"{item['title']} {'added to' if item['favorite'] else 'removed from'} favorites."
                break
        render_detail()
        refresh_gallery()

    def delete_item(item_id: str) -> None:
        state["items"] = [item for item in state["items"] if item["id"] != item_id]
        remaining = filtered_items()
        state["selected_id"] = remaining[0]["id"] if remaining else (state["items"][0]["id"] if state["items"] else None)
        detail_status.value = "Prototype item removed from the collection."
        update_filter_options()
        render_detail()
        refresh_gallery()

    def add_item(_: ft.ControlEvent) -> None:
        title = (title_input.value or "").strip()
        notes = (notes_input.value or "").strip()
        tags = [tag.strip() for tag in (tags_input.value or "").split(",") if tag.strip()]
        color = normalize_color(color_input.value or "")

        if not title:
            form_message.value = "Add a title so the prototype has something to display."
            page.update()
            return

        new_item = {
            "id": f"img-{len(state['items']) + 1:02d}",
            "title": title,
            "color": color,
            "tags": tags or ["untagged"],
            "notes": notes or "No notes added yet.",
            "favorite": False,
        }
        state["items"].insert(0, new_item)
        state["selected_id"] = new_item["id"]
        title_input.value = ""
        color_input.value = "#7B6CF6"
        tags_input.value = ""
        notes_input.value = ""
        form_message.value = "Prototype item added."
        detail_status.value = "New prototype tile created."
        update_filter_options()
        render_detail()
        refresh_gallery()

    def render_detail() -> None:
        item = current_selection()
        detail_tags.controls.clear()
        if not item:
            detail_title.value = "No items left"
            detail_square.bgcolor = "#D8C7B7"
            detail_notes.value = "Add a new tile to continue prototyping."
            page.update()
            return

        detail_title.value = item["title"]
        detail_square.bgcolor = item["color"]
        detail_notes.value = item["notes"]
        for tag in item["tags"]:
            detail_tags.controls.append(
                ft.Container(
                    padding=ft.padding.symmetric(horizontal=12, vertical=8),
                    border_radius=999,
                    bgcolor="#DCEFEB",
                    content=ft.Text(tag, color="#20585A", size=13),
                )
            )
        page.update()

    def make_card(item: dict) -> ft.Control:
        is_selected = item["id"] == state["selected_id"]

        favorite_button = ft.TextButton(
            "Favorited" if item["favorite"] else "Favorite",
            style=ft.ButtonStyle(
                color="#FFF8F4" if item["favorite"] else "#8A3C1D",
                bgcolor="#B6532F" if item["favorite"] else "#F3DDD3",
                shape=ft.RoundedRectangleBorder(radius=999),
            ),
            on_click=lambda _, item_id=item["id"]: toggle_favorite(item_id),
        )

        card = ft.Container(
            col={"xs": 12, "sm": 6, "xl": 4},
            padding=18,
            border_radius=24,
            bgcolor="#FFFBF6" if not is_selected else "#FFF2E7",
            border=ft.border.all(2 if is_selected else 1, "#D5744E" if is_selected else "#E0D0C1"),
            ink=True,
            on_click=lambda _, item_id=item["id"]: select_item(item_id),
            content=ft.Column(
                spacing=14,
                controls=[
                    ft.Container(
                        height=170,
                        border_radius=18,
                        bgcolor=item["color"],
                        padding=18,
                        content=ft.Column(
                            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
                            controls=[
                                ft.Text("IMAGE", color="#FFF8F4", weight=ft.FontWeight.W_700),
                                ft.Row(
                                    alignment=ft.MainAxisAlignment.END,
                                    controls=[
                                        ft.Container(width=52, height=52, border_radius=16, bgcolor="#FFFFFF22"),
                                    ],
                                ),
                            ],
                        ),
                    ),
                    ft.Text(item["title"], size=18, weight=ft.FontWeight.W_600, color="#1F1D1A"),
                    ft.Text(item["notes"], size=14, color="#5E574F", max_lines=2, overflow=ft.TextOverflow.ELLIPSIS),
                    ft.Row(wrap=True, spacing=8, controls=[
                        ft.Container(
                            padding=ft.padding.symmetric(horizontal=10, vertical=6),
                            border_radius=999,
                            bgcolor="#E6F1EF",
                            content=ft.Text(tag, size=12, color="#20585A"),
                        )
                        for tag in item["tags"][:3]
                    ]),
                    ft.Row(
                        alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
                        controls=[
                            favorite_button,
                            ft.IconButton(
                                icon=ft.Icons.DELETE_OUTLINE,
                                icon_color="#6B6259",
                                tooltip="Remove tile",
                                on_click=lambda _, item_id=item["id"]: delete_item(item_id),
                            ),
                        ],
                    ),
                ],
            ),
        )
        return card

    def refresh_gallery() -> None:
        matches = filtered_items()
        results_label.value = f"{len(matches)} result{'s' if len(matches) != 1 else ''} in your inspiration library"
        gallery_grid.controls = [make_card(item) for item in matches]
        if not matches:
            gallery_grid.controls = [
                ft.Container(
                    col={"xs": 12},
                    padding=26,
                    border_radius=24,
                    bgcolor="#FFF9F2",
                    border=ft.border.all(1, "#D8C7B7"),
                    content=ft.Text(
                        "No prototype tiles match the current search. Try another tag or add a new tile.",
                        color="#6B6259",
                        size=15,
                    ),
                )
            ]
        page.update()

    update_filter_options()

    page.add(
        ft.Container(
            expand=True,
            padding=ft.padding.symmetric(horizontal=28, vertical=26),
            content=ft.Column(
                expand=True,
                spacing=24,
                controls=[
                    ft.Container(
                        padding=32,
                        border_radius=32,
                        gradient=ft.LinearGradient(
                            begin=ft.alignment.top_left,
                            end=ft.alignment.bottom_right,
                            colors=["#FFF9F0", "#FFE9D9"],
                        ),
                        content=ft.Row(
                            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
                            vertical_alignment=ft.CrossAxisAlignment.END,
                            controls=[
                                ft.Column(
                                    spacing=12,
                                    controls=[
                                        ft.Text("Visual Research Archive", size=13, color="#8A3C1D", weight=ft.FontWeight.W_700),
                                        ft.Text(
                                            "Clickable prototype for tracking interesting images.",
                                            size=46,
                                            color="#1F1D1A",
                                            weight=ft.FontWeight.W_500,
                                            font_family="Display",
                                        ),
                                        ft.Text(
                                            "Colored squares stand in for real images while you test the save, filter, favorite, and browse flow.",
                                            size=16,
                                            color="#6B6259",
                                        ),
                                    ],
                                ),
                                ft.Container(
                                    width=250,
                                    padding=24,
                                    border_radius=28,
                                    bgcolor="#B6532F",
                                    content=ft.Column(
                                        spacing=8,
                                        controls=[
                                            ft.Text("Prototype mode", size=13, color="#FFDACA", weight=ft.FontWeight.W_700),
                                            ft.Text("Flet UI", size=28, color="#FFF8F4", weight=ft.FontWeight.W_700),
                                            ft.Text("Swap the squares for real thumbnails later without changing the interaction model.", size=14, color="#FFEDE5"),
                                        ],
                                    ),
                                ),
                            ],
                        ),
                    ),
                    ft.Row(
                        expand=True,
                        vertical_alignment=ft.CrossAxisAlignment.START,
                        controls=[
                            ft.Container(
                                width=340,
                                padding=24,
                                border_radius=28,
                                bgcolor="#FFFBF6",
                                border=ft.border.all(1, "#E2D2C3"),
                                content=ft.Column(
                                    spacing=18,
                                    controls=[
                                        ft.Text("Add prototype tile", size=28, font_family="Display", color="#1F1D1A"),
                                        title_input,
                                        color_input,
                                        tags_input,
                                        notes_input,
                                        ft.ElevatedButton(
                                            "Create tile",
                                            style=ft.ButtonStyle(
                                                padding=20,
                                                bgcolor="#B6532F",
                                                color="#FFF8F4",
                                                shape=ft.RoundedRectangleBorder(radius=18),
                                            ),
                                            on_click=add_item,
                                        ),
                                        form_message,
                                    ],
                                ),
                            ),
                            ft.Container(
                                expand=True,
                                padding=24,
                                border_radius=28,
                                bgcolor="#FFFCF8",
                                border=ft.border.all(1, "#E2D2C3"),
                                content=ft.Column(
                                    expand=True,
                                    spacing=18,
                                    controls=[
                                        ft.Row(
                                            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
                                            controls=[
                                                ft.Text("Browse collection", size=28, font_family="Display", color="#1F1D1A"),
                                                favorites_only,
                                            ],
                                        ),
                                        ft.Row(controls=[search_field, tag_filter]),
                                        results_label,
                                        ft.Container(expand=True, content=ft.ListView(expand=True, controls=[gallery_grid])),
                                    ],
                                ),
                            ),
                            ft.Container(
                                width=360,
                                padding=24,
                                border_radius=28,
                                bgcolor="#FFF4EA",
                                border=ft.border.all(1, "#E5CDBB"),
                                content=ft.Column(
                                    spacing=18,
                                    controls=[
                                        ft.Text("Selected image", size=28, font_family="Display", color="#1F1D1A"),
                                        detail_square,
                                        detail_title,
                                        detail_tags,
                                        detail_notes,
                                        detail_status,
                                    ],
                                ),
                            ),
                        ],
                    ),
                ],
            ),
        )
    )

    render_detail()
    refresh_gallery()


if __name__ == "__main__":
    ft.app(target=main)
