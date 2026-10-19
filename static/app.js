const state = {
    page: document.body.dataset.page,
    currentUser: null,
    authMode: "login",
    imageCache: [],
    selectedImageIds: [],
    favoritesData: { favorites: [], folders: [], user: null },
    activeFolderId: "all",
    dashboardLinkStart: null,
};

document.addEventListener("DOMContentLoaded", () => {
    state.selectedImageIds = getSelectedImageIds();
    initializeCommonUi();
    initializePage();
    boot();
});

function initializePage() {
    if (state.page === "library") {
        initializeLibraryPage();
    } else if (state.page === "favorites") {
        initializeFavoritesPage();
    } else if (state.page === "image") {
        initializeImagePage();
    } else if (state.page === "timeline") {
        initializeTimelinePage();
    } else if (state.page === "dashboard") {
        initializeDashboardPage();
    }
}

async function boot() {
    await refreshSession();

    if (state.page === "library") {
        await loadImages();
    } else if (state.page === "favorites") {
        if (state.currentUser) {
            await loadFavoritesData();
        } else {
            renderFavoritesLoggedOut();
        }
    } else if (state.page === "image") {
        await loadImageDetailPage();
    } else if (state.page === "timeline") {
        await loadTimelinePage();
    } else if (state.page === "dashboard") {
        ensureBoardStore();
        await loadDashboardPage();
    }
}

function initializeCommonUi() {
    document.getElementById("open-login")?.addEventListener("click", () => openAuthModal("login"));
    document.getElementById("open-register")?.addEventListener("click", () => openAuthModal("register"));
    document.getElementById("close-auth")?.addEventListener("click", closeAuthModal);
    document.getElementById("auth-modal")?.addEventListener("click", (event) => {
        if (event.target instanceof HTMLElement && event.target.dataset.closeAuth === "true") {
            closeAuthModal();
        }
    });
    document.getElementById("auth-form")?.addEventListener("submit", submitAuthForm);
    document.getElementById("logout-button")?.addEventListener("click", logout);

    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            closeAuthModal();
            closeFormModal();
            closeFolderModal();
        }
    });
}

function initializeLibraryPage() {
    document.getElementById("open-add-image")?.addEventListener("click", openFormModal);
    document.getElementById("close-form")?.addEventListener("click", closeFormModal);
    document.getElementById("form-modal")?.addEventListener("click", (event) => {
        if (event.target instanceof HTMLElement && event.target.dataset.closeForm === "true") {
            closeFormModal();
        }
    });
    document.getElementById("image-form")?.addEventListener("submit", createImage);
    document.getElementById("search-input")?.addEventListener("input", debounce(loadImages, 220));
    document.getElementById("tag-filter")?.addEventListener("input", debounce(loadImages, 220));
    document.getElementById("selection-sort")?.addEventListener("change", renderSelectionGallery);

    document.getElementById("clear-selection")?.addEventListener("click", () => {
        state.selectedImageIds = [];
        persistSelectedImageIds();
        renderLibraryGallery();
        renderSelectionGallery();
    });

    document.getElementById("select-visible")?.addEventListener("click", () => {
        const merged = new Set(state.selectedImageIds);
        for (const image of state.imageCache) {
            merged.add(image.id);
        }
        state.selectedImageIds = Array.from(merged);
        persistSelectedImageIds();
        renderLibraryGallery();
        renderSelectionGallery();
    });

    document.getElementById("send-selected-dashboard")?.addEventListener("click", () => {
        if (state.selectedImageIds.length === 0) {
            document.getElementById("selection-meta").textContent = "Select at least one image first.";
            return;
        }
        addImagesToActiveBoard(state.selectedImageIds);
        window.location.href = "/dashboard.html";
    });

    document.getElementById("show-selected-timeline")?.addEventListener("click", () => {
        if (state.selectedImageIds.length === 0) {
            document.getElementById("selection-meta").textContent = "Select at least one image first.";
            return;
        }
        persistTimelineMode("selected");
        window.location.href = "/timeline.html";
    });
}

function initializeFavoritesPage() {
    document.getElementById("new-folder-button")?.addEventListener("click", () => openFolderModal());
    document.getElementById("close-folder")?.addEventListener("click", closeFolderModal);
    document.getElementById("folder-modal")?.addEventListener("click", (event) => {
        if (event.target instanceof HTMLElement && event.target.dataset.closeFolder === "true") {
            closeFolderModal();
        }
    });
    document.getElementById("folder-form")?.addEventListener("submit", saveFolder);
    document.getElementById("folder-delete")?.addEventListener("click", deleteFolder);
}

function initializeImagePage() {
    document.getElementById("detail-favorite")?.addEventListener("click", async () => {
        const imageId = currentImageId();
        if (!imageId) {
            return;
        }
        await toggleFavorite(imageId);
        await loadImageDetailPage();
    });
}

function initializeTimelinePage() {
    document.getElementById("timeline-selected-only")?.addEventListener("click", async () => {
        persistTimelineMode("selected");
        await loadTimelinePage();
    });
    document.getElementById("timeline-show-all")?.addEventListener("click", async () => {
        persistTimelineMode("all");
        await loadTimelinePage();
    });
}

function initializeDashboardPage() {
    document.getElementById("clear-dashboard")?.addEventListener("click", () => {
        clearActiveBoard();
        state.dashboardLinkStart = null;
        renderDashboardPage();
    });

    document.getElementById("import-selected-board")?.addEventListener("click", () => {
        if (state.selectedImageIds.length === 0) {
            document.getElementById("dashboard-meta").textContent = "Select images in the library first.";
            return;
        }
        addImagesToActiveBoard(state.selectedImageIds);
        renderDashboardPage();
    });

    document.getElementById("new-board-button")?.addEventListener("click", () => {
        const store = getBoardStore();
        if (store.boards.length >= 10) {
            document.getElementById("dashboard-sidebar-meta").textContent = "You can save up to 10 boards.";
            return;
        }
        const name = window.prompt(`Name for board ${store.boards.length + 1}:`, `Board ${store.boards.length + 1}`);
        if (!name || !name.trim()) {
            return;
        }
        createBoard(name.trim());
        renderDashboardPage();
    });

    const board = document.getElementById("dashboard-board");
    board?.addEventListener("dragover", (event) => {
        event.preventDefault();
        board.classList.add("is-drop-target");
    });
    board?.addEventListener("dragleave", () => {
        board.classList.remove("is-drop-target");
    });
    board?.addEventListener("drop", (event) => {
        event.preventDefault();
        board.classList.remove("is-drop-target");
        const imageId = event.dataTransfer?.getData("text/image-id");
        if (!imageId) {
            return;
        }
        addImagesToActiveBoard([imageId]);
        renderDashboardPage();
    });
}

async function refreshSession() {
    const payload = await apiRequest("/api/auth/me");
    state.currentUser = payload.user;
    state.selectedImageIds = getSelectedImageIds();
    renderAuthState();
}

function renderAuthState() {
    const authStatus = document.getElementById("auth-status");
    const authControls = document.getElementById("auth-controls");
    const userControls = document.getElementById("user-controls");
    const userLabel = document.getElementById("user-label");
    if (!authStatus || !authControls || !userControls || !userLabel) {
        return;
    }

    if (state.currentUser) {
        authStatus.textContent = "Logged in";
        authControls.classList.add("is-hidden");
        userControls.classList.remove("is-hidden");
        userLabel.textContent = `@${state.currentUser.username}`;
    } else {
        authStatus.textContent = "Log in to save favorites";
        authControls.classList.remove("is-hidden");
        userControls.classList.add("is-hidden");
    }
}

function openAuthModal(mode) {
    state.authMode = mode;
    const modal = document.getElementById("auth-modal");
    const title = document.getElementById("auth-modal-title");
    const kicker = document.getElementById("auth-modal-kicker");
    const submit = document.getElementById("auth-submit");
    const message = document.getElementById("auth-message");
    if (!modal || !title || !kicker || !submit || !message) {
        return;
    }
    title.textContent = mode === "register" ? "Create account" : "Log in";
    kicker.textContent = mode === "register" ? "New account" : "Account";
    submit.textContent = mode === "register" ? "Register" : "Log in";
    message.textContent = "";
    modal.classList.remove("is-hidden");
    document.body.style.overflow = "hidden";
}

function closeAuthModal() {
    const modal = document.getElementById("auth-modal");
    if (modal) {
        modal.classList.add("is-hidden");
        document.body.style.overflow = "";
    }
}

async function submitAuthForm(event) {
    event.preventDefault();
    const username = document.getElementById("auth-username").value.trim();
    const password = document.getElementById("auth-password").value;
    const message = document.getElementById("auth-message");
    try {
        const endpoint = state.authMode === "register" ? "/api/auth/register" : "/api/auth/login";
        await apiRequest(endpoint, {
            method: "POST",
            body: JSON.stringify({ username, password }),
        });
        closeAuthModal();
        await refreshSession();
        if (state.page === "library") {
            await loadImages();
        } else if (state.page === "favorites") {
            await loadFavoritesData();
        } else if (state.page === "timeline") {
            await loadTimelinePage();
        } else if (state.page === "dashboard") {
            ensureBoardStore();
            await loadDashboardPage();
        }
    } catch (error) {
        message.textContent = error.message;
    }
}

async function logout() {
    await apiRequest("/api/auth/logout", { method: "POST" });
    state.currentUser = null;
    state.selectedImageIds = getSelectedImageIds();
    renderAuthState();
    if (state.page === "library") {
        await loadImages();
    } else if (state.page === "favorites") {
        renderFavoritesLoggedOut();
    } else if (state.page === "timeline") {
        await loadTimelinePage();
    } else if (state.page === "dashboard") {
        await loadDashboardPage();
    }
}

async function fetchImageCollection(searchValue = "", tagValue = "") {
    const params = new URLSearchParams();
    if (searchValue) {
        params.set("query", searchValue);
    }
    if (tagValue) {
        params.set("tag", tagValue);
    }
    const query = params.toString();
    return apiRequest(`/api/images${query ? `?${query}` : ""}`);
}

async function loadImages() {
    const searchValue = document.getElementById("search-input")?.value.trim() || "";
    const tagValue = document.getElementById("tag-filter")?.value.trim() || "";
    const payload = await fetchImageCollection(searchValue, tagValue);
    state.imageCache = payload.images;
    state.selectedImageIds = getSelectedImageIds().filter((id) => state.imageCache.some((image) => image.id === id));
    persistSelectedImageIds();
    renderLibraryGallery();
    renderSelectionGallery();
}

function renderLibraryGallery() {
    const gallery = document.getElementById("gallery");
    const meta = document.getElementById("results-meta");
    if (!gallery || !meta) {
        return;
    }

    gallery.innerHTML = "";
    meta.textContent = `${state.imageCache.length} image${state.imageCache.length === 1 ? "" : "s"} found`;

    if (state.imageCache.length === 0) {
        gallery.appendChild(createEmptyState("No images match your current search yet."));
        return;
    }

    for (const image of state.imageCache) {
        gallery.appendChild(buildLibraryCard(image));
    }
}

function buildLibraryCard(image) {
    const template = document.getElementById("image-card-template");
    const node = template.content.firstElementChild.cloneNode(true);

    node.querySelector("img").src = image.imageUrl;
    node.querySelector("img").alt = image.title;
    node.querySelector("h3").textContent = image.title;
    node.querySelector(".meta-line").textContent = formatMetaLine(image);
    node.querySelector(".caption-line").textContent = image.imageCaption || "No caption added.";
    node.querySelector(".description").textContent = image.imageDescription || "No detailed description added.";
    node.querySelector("time").textContent = new Date(image.createdAt).toLocaleDateString();

    const favoriteButton = node.querySelector(".favorite-button");
    favoriteButton.textContent = image.favorite ? "Saved" : "Save";
    favoriteButton.classList.toggle("is-active", image.favorite);
    favoriteButton.addEventListener("click", () => toggleFavorite(image.id));

    const selectButton = node.querySelector(".select-button");
    if (selectButton) {
        selectButton.textContent = state.selectedImageIds.includes(image.id) ? "Selected" : "Select";
        selectButton.classList.toggle("is-active", state.selectedImageIds.includes(image.id));
        selectButton.addEventListener("click", () => toggleSelection(image.id));
    }

    const detailLink = node.querySelector(".detail-link");
    if (detailLink) {
        detailLink.href = imageDetailUrl(image.id);
    }

    const tags = node.querySelector(".tags");
    for (const tag of image.tags) {
        const chip = document.createElement("span");
        chip.textContent = tag;
        tags.appendChild(chip);
    }

    enableCardNavigation(node, image.id);
    return node;
}

function renderSelectionGallery() {
    const gallery = document.getElementById("selection-gallery");
    const meta = document.getElementById("selection-meta");
    const sortValue = document.getElementById("selection-sort")?.value || "year-desc";
    if (!gallery || !meta) {
        return;
    }

    const selected = state.selectedImageIds
        .map((id) => state.imageCache.find((image) => image.id === id))
        .filter(Boolean);

    gallery.innerHTML = "";
    meta.textContent = `${selected.length} image${selected.length === 1 ? "" : "s"} temporarily selected`;

    if (selected.length === 0) {
        gallery.appendChild(createEmptyState("Select images from the library to build a comparison subset."));
        return;
    }

    const sorted = sortSelectedImages(selected, sortValue);
    for (const image of sorted) {
        gallery.appendChild(buildLibraryCard(image));
    }
}

function sortSelectedImages(images, mode) {
    const sorted = [...images];
    sorted.sort((left, right) => {
        if (mode === "year-asc") {
            return compareDates(left.dateTaken, right.dateTaken);
        }
        if (mode === "year-desc") {
            return compareDates(right.dateTaken, left.dateTaken);
        }
        if (mode === "paper-desc") {
            return compareText(right.paper, left.paper);
        }
        return compareText(left.paper, right.paper);
    });
    return sorted;
}

function toggleSelection(id) {
    if (state.selectedImageIds.includes(id)) {
        state.selectedImageIds = state.selectedImageIds.filter((value) => value !== id);
    } else {
        state.selectedImageIds = [...state.selectedImageIds, id];
    }
    persistSelectedImageIds();
    renderLibraryGallery();
    renderSelectionGallery();
}

function openFormModal() {
    if (!state.currentUser) {
        openAuthModal("login");
        return;
    }
    const modal = document.getElementById("form-modal");
    modal?.classList.remove("is-hidden");
    document.body.style.overflow = "hidden";
}

function closeFormModal() {
    const modal = document.getElementById("form-modal");
    if (modal) {
        modal.classList.add("is-hidden");
        document.body.style.overflow = "";
    }
}

async function createImage(event) {
    event.preventDefault();
    const form = document.getElementById("image-form");
    const message = document.getElementById("form-message");
    try {
        const payload = {
            title: form.title.value.trim(),
            imageUrl: form.imageUrl.value.trim(),
            imageCaption: form.imageCaption.value.trim(),
            imageDescription: form.imageDescription.value.trim(),
            locationTaken: form.locationTaken.value.trim(),
            dateTaken: form.dateTaken.value.trim(),
            fileSize: form.fileSize.value.trim(),
            fileType: form.fileType.value.trim(),
            resolution: form.resolution.value.trim(),
            cameraMetadata: parseCameraMetadata(form.cameraMetadata.value.trim()),
            colorProfile: form.colorProfile.value.trim(),
            paper: form.paper.value.trim(),
            sourceUrl: form.sourceUrl.value.trim(),
            creator: form.creator.value.trim(),
            copyrightStatus: form.copyrightStatus.value.trim(),
            licenseType: form.licenseType.value.trim(),
            dateCreated: form.dateCreated.value.trim(),
            dateModified: form.dateModified.value.trim(),
            editedStatus: form.editedStatus.value === "true",
            editor: form.editor.value.trim(),
            version: form.version.value.trim(),
            tags: form.tags.value.split(",").map((tag) => tag.trim()).filter(Boolean),
            notes: form.notes.value.trim(),
        };
        await apiRequest("/api/images", {
            method: "POST",
            body: JSON.stringify(payload),
        });
        form.reset();
        message.textContent = "Image saved.";
        closeFormModal();
        await loadImages();
    } catch (error) {
        message.textContent = error.message;
    }
}

function parseCameraMetadata(value) {
    if (!value) {
        return {};
    }
    try {
        const parsed = JSON.parse(value);
        return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
    } catch (error) {
        return { raw: value };
    }
}

async function toggleFavorite(imageId) {
    if (!state.currentUser) {
        openAuthModal("login");
        return;
    }
    await apiRequest(`/api/images/${imageId}/favorite`, { method: "PUT" });

    if (state.page === "library") {
        await loadImages();
    } else if (state.page === "favorites") {
        await loadFavoritesData();
    } else if (state.page === "image") {
        await loadImageDetailPage();
    } else if (state.page === "timeline") {
        await loadTimelinePage();
    } else if (state.page === "dashboard") {
        await loadDashboardPage();
    }
}

async function loadFavoritesData() {
    try {
        const payload = await apiRequest("/api/me/favorites");
        state.favoritesData = payload;
        renderFavoritesPage();
    } catch (error) {
        renderFavoritesLoggedOut(error.message);
    }
}

function renderFavoritesLoggedOut(message = "Log in to see your saved images, folders, and private notes.") {
    const folderList = document.getElementById("folder-list");
    const gallery = document.getElementById("favorites-gallery");
    const meta = document.getElementById("favorites-meta");
    const title = document.getElementById("favorites-title");

    if (folderList) {
        folderList.innerHTML = "";
        folderList.appendChild(createEmptyState(message));
    }
    if (gallery) {
        gallery.innerHTML = "";
        gallery.appendChild(createEmptyState(message));
    }
    if (meta) {
        meta.textContent = "No personal data loaded";
    }
    if (title) {
        title.textContent = "All saved images";
    }
}

function renderFavoritesPage() {
    renderFolderList();
    renderFavoritesGallery();
}

function renderFolderList() {
    const folderList = document.getElementById("folder-list");
    if (!folderList) {
        return;
    }
    folderList.innerHTML = "";

    folderList.appendChild(buildFolderItem({ id: "all", name: "All saved images", note: "Everything you have favorited." }, false));
    for (const folder of state.favoritesData.folders) {
        folderList.appendChild(buildFolderItem(folder, true));
    }

    if (state.favoritesData.folders.length === 0) {
        folderList.appendChild(createEmptyState("Create a folder to start sorting your saved images."));
    }
}

function buildFolderItem(folder, editable) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `folder-item${state.activeFolderId === folder.id ? " is-active" : ""}`;
    button.innerHTML = `
        <h3>${escapeHtml(folder.name)}</h3>
        <p>${escapeHtml(folder.note || "No private folder note yet.")}</p>
    `;
    button.addEventListener("click", () => {
        state.activeFolderId = folder.id;
        renderFavoritesPage();
    });
    if (editable) {
        button.addEventListener("dblclick", () => openFolderModal(folder));
    }
    return button;
}

function renderFavoritesGallery() {
    const gallery = document.getElementById("favorites-gallery");
    const meta = document.getElementById("favorites-meta");
    const title = document.getElementById("favorites-title");
    if (!gallery || !meta || !title) {
        return;
    }

    const favorites = getVisibleFavorites();
    gallery.innerHTML = "";
    title.textContent = state.activeFolderId === "all"
        ? "All saved images"
        : state.favoritesData.folders.find((folder) => folder.id === state.activeFolderId)?.name || "Saved images";
    meta.textContent = `${favorites.length} saved image${favorites.length === 1 ? "" : "s"} visible`;

    if (favorites.length === 0) {
        gallery.appendChild(createEmptyState("No saved images in this view yet."));
        return;
    }

    const template = document.getElementById("favorite-card-template");
    for (const favorite of favorites) {
        const node = template.content.firstElementChild.cloneNode(true);
        const image = favorite.image;
        node.querySelector("img").src = image.imageUrl;
        node.querySelector("img").alt = image.title;
        node.querySelector("h3").textContent = image.title;
        node.querySelector(".meta-line").textContent = formatMetaLine(image);
        node.querySelector(".caption-line").textContent = image.imageCaption || "No caption added.";
        const detailLink = node.querySelector(".detail-link");
        if (detailLink) {
            detailLink.href = imageDetailUrl(image.id);
        }
        enableCardNavigation(node, image.id);

        const favoriteButton = node.querySelector(".favorite-button");
        favoriteButton.addEventListener("click", () => toggleFavorite(image.id));

        const folderChipRow = node.querySelector(".folder-chip-row");
        const attachedFolders = favorite.folderIds
            .map((folderId) => state.favoritesData.folders.find((folder) => folder.id === folderId))
            .filter(Boolean);
        for (const folder of attachedFolders) {
            const chip = document.createElement("span");
            chip.textContent = folder.name;
            folderChipRow.appendChild(chip);
        }
        if (attachedFolders.length === 0) {
            const chip = document.createElement("span");
            chip.textContent = "Unsorted";
            folderChipRow.appendChild(chip);
        }

        const noteField = node.querySelector("textarea");
        noteField.value = favorite.note || "";
        noteField.addEventListener("change", () => saveFavoriteNote(image.id, noteField.value));

        const folderSelect = node.querySelector("select");
        for (const folder of state.favoritesData.folders) {
            const option = document.createElement("option");
            option.value = folder.id;
            option.textContent = folder.name;
            option.selected = favorite.folderIds.includes(folder.id);
            folderSelect.appendChild(option);
        }
        folderSelect.addEventListener("change", () => {
            const folderIds = Array.from(folderSelect.selectedOptions).map((option) => option.value);
            saveFavoriteFolders(image.id, folderIds);
        });

        gallery.appendChild(node);
    }
}

function getVisibleFavorites() {
    if (state.activeFolderId === "all") {
        return state.favoritesData.favorites;
    }
    return state.favoritesData.favorites.filter((favorite) => favorite.folderIds.includes(state.activeFolderId));
}

function openFolderModal(folder = null) {
    if (!state.currentUser) {
        openAuthModal("login");
        return;
    }
    document.getElementById("folder-id").value = folder?.id || "";
    document.getElementById("folder-name").value = folder?.name || "";
    document.getElementById("folder-note").value = folder?.note || "";
    document.getElementById("folder-message").textContent = "";
    document.getElementById("folder-modal-title").textContent = folder ? "Edit folder" : "Create folder";
    document.getElementById("folder-delete").classList.toggle("is-hidden", !folder);
    document.getElementById("folder-modal").classList.remove("is-hidden");
    document.body.style.overflow = "hidden";
}

function closeFolderModal() {
    const modal = document.getElementById("folder-modal");
    if (modal) {
        modal.classList.add("is-hidden");
        document.body.style.overflow = "";
    }
}

async function saveFolder(event) {
    event.preventDefault();
    const folderId = document.getElementById("folder-id").value;
    const name = document.getElementById("folder-name").value.trim();
    const note = document.getElementById("folder-note").value.trim();
    const message = document.getElementById("folder-message");
    try {
        if (folderId) {
            await apiRequest(`/api/me/folders/${folderId}`, {
                method: "PUT",
                body: JSON.stringify({ name, note }),
            });
        } else {
            await apiRequest("/api/me/folders", {
                method: "POST",
                body: JSON.stringify({ name, note }),
            });
        }
        closeFolderModal();
        await loadFavoritesData();
    } catch (error) {
        message.textContent = error.message;
    }
}

async function deleteFolder() {
    const folderId = document.getElementById("folder-id").value;
    if (!folderId) {
        return;
    }
    await apiRequest(`/api/me/folders/${folderId}`, { method: "DELETE" });
    if (state.activeFolderId === folderId) {
        state.activeFolderId = "all";
    }
    closeFolderModal();
    await loadFavoritesData();
}

async function saveFavoriteNote(imageId, note) {
    await apiRequest(`/api/me/favorites/${imageId}/note`, {
        method: "PUT",
        body: JSON.stringify({ note }),
    });
    await loadFavoritesData();
}

async function saveFavoriteFolders(imageId, folderIds) {
    await apiRequest(`/api/me/favorites/${imageId}/folders`, {
        method: "PUT",
        body: JSON.stringify({ folderIds }),
    });
    await loadFavoritesData();
}

async function loadImageDetailPage() {
    const imageId = currentImageId();
    const title = document.getElementById("detail-title");
    const relatedGallery = document.getElementById("related-gallery");
    const relatedMeta = document.getElementById("related-meta");
    if (!imageId) {
        title.textContent = "Image not found";
        relatedGallery.innerHTML = "";
        relatedGallery.appendChild(createEmptyState("No image id was provided in the URL."));
        return;
    }

    try {
        const payload = await apiRequest(`/api/images/${encodeURIComponent(imageId)}`);
        renderImageDetail(payload.image, payload.relatedImages || []);
    } catch (error) {
        title.textContent = "Image not found";
        relatedMeta.textContent = error.message;
        relatedGallery.innerHTML = "";
        relatedGallery.appendChild(createEmptyState("The requested image could not be loaded."));
    }
}

function renderImageDetail(image, relatedImages) {
    document.getElementById("detail-image").src = image.imageUrl;
    document.getElementById("detail-image").alt = image.title;
    document.getElementById("detail-title").textContent = image.title;
    document.getElementById("detail-caption").textContent = image.imageCaption || "No caption added.";
    document.getElementById("detail-description").textContent = image.imageDescription || "No detailed description added.";

    const favoriteButton = document.getElementById("detail-favorite");
    favoriteButton.textContent = image.favorite ? "Saved" : "Save";
    favoriteButton.classList.toggle("is-active", image.favorite);

    renderMetadataGroups(image);

    const relatedGallery = document.getElementById("related-gallery");
    const relatedMeta = document.getElementById("related-meta");
    relatedGallery.innerHTML = "";
    relatedMeta.textContent = `${relatedImages.length} related image${relatedImages.length === 1 ? "" : "s"} suggested`;
    if (relatedImages.length === 0) {
        relatedGallery.appendChild(createEmptyState("No similar images were strong enough matches yet."));
        return;
    }

    for (const related of relatedImages) {
        relatedGallery.appendChild(buildRelatedCard(related));
    }
}

function renderMetadataGroups(image) {
    const groups = document.getElementById("metadata-groups");
    groups.innerHTML = "";
    const sections = [
        {
            title: "Descriptive metadata",
            items: [
                ["Title", image.title],
                ["Image caption", image.imageCaption],
                ["Image description", image.imageDescription],
                ["Keywords / tags", image.tags.join(", ")],
                ["Location taken", image.locationTaken],
                ["Date taken", image.dateTaken],
            ],
        },
        {
            title: "Technical metadata",
            items: [
                ["File size", formatFileSize(image.fileSize)],
                ["File type", image.fileType],
                ["Resolution", image.resolution],
                ["Camera metadata (EXIF)", formatCameraMetadata(image.cameraMetadata)],
                ["Colour profile", image.colorProfile],
            ],
        },
        {
            title: "Administrative and provenance metadata",
            items: [
                ["Paper / newspaper", image.paper],
                ["Source URL", image.sourceUrl],
                ["Creator / author", image.creator],
                ["Copyright status", image.copyrightStatus],
                ["License type", image.licenseType],
                ["Date created", image.dateCreated],
                ["Date modified", image.dateModified],
                ["Edited status", image.editedStatus ? "Edited" : "Not edited"],
                ["Editor", image.editor],
                ["Version", image.version],
                ["Archive notes", image.notes],
            ],
        },
    ];

    for (const section of sections) {
        const card = document.createElement("section");
        card.className = "metadata-card";
        card.innerHTML = `<h3>${section.title}</h3>`;
        const list = document.createElement("dl");
        list.className = "metadata-list";
        for (const [label, value] of section.items) {
            const row = document.createElement("div");
            row.className = "metadata-row";
            const dt = document.createElement("dt");
            dt.textContent = label;
            const dd = document.createElement("dd");
            dd.textContent = value || "Not provided";
            row.appendChild(dt);
            row.appendChild(dd);
            list.appendChild(row);
        }
        card.appendChild(list);
        groups.appendChild(card);
    }
}

function buildRelatedCard(image) {
    const template = document.getElementById("image-card-template");
    const node = template.content.firstElementChild.cloneNode(true);
    node.querySelector("img").src = image.imageUrl;
    node.querySelector("img").alt = image.title;
    node.querySelector("h3").textContent = image.title;
    node.querySelector(".meta-line").textContent = formatMetaLine(image);
    node.querySelector(".caption-line").textContent = image.imageCaption || "No caption added.";
    node.querySelector(".description").textContent = image.imageDescription || "No detailed description added.";
    node.querySelector("time").textContent = image.dateTaken || "Unknown date";

    const favoriteButton = node.querySelector(".favorite-button");
    favoriteButton.textContent = image.favorite ? "Saved" : "Save";
    favoriteButton.classList.toggle("is-active", image.favorite);
    favoriteButton.addEventListener("click", () => toggleFavorite(image.id));

    const detailLink = node.querySelector(".detail-link");
    if (detailLink) {
        detailLink.href = imageDetailUrl(image.id);
    }

    const selectButton = node.querySelector(".select-button");
    if (selectButton) {
        selectButton.remove();
    }

    const tags = node.querySelector(".tags");
    for (const tag of image.tags) {
        const chip = document.createElement("span");
        chip.textContent = tag;
        tags.appendChild(chip);
    }

    enableCardNavigation(node, image.id);
    return node;
}

function enableCardNavigation(node, imageId) {
    const detailUrl = imageDetailUrl(imageId);
    for (const target of [node.querySelector(".image-frame"), node.querySelector("h3"), node.querySelector("img")]) {
        if (!target) {
            continue;
        }
        target.classList.add("clickable-detail");
        target.addEventListener("click", () => {
            window.location.href = detailUrl;
        });
    }
}

async function loadTimelinePage() {
    const payload = await fetchImageCollection();
    const mode = getTimelineMode();
    const sourceImages = mode === "selected"
        ? payload.images.filter((image) => state.selectedImageIds.includes(image.id))
        : payload.images;
    state.imageCache = sourceImages
        .filter((image) => image.dateTaken && Number.isFinite(Date.parse(image.dateTaken)))
        .sort((left, right) => Date.parse(left.dateTaken) - Date.parse(right.dateTaken))
        .slice(0, 10);
    renderTimelinePage();
}

function renderTimelinePage() {
    const shell = document.getElementById("timeline-shell");
    const meta = document.getElementById("timeline-meta");
    if (!shell || !meta) {
        return;
    }

    const datedImages = state.imageCache;

    shell.innerHTML = "";
    meta.textContent = `${datedImages.length} image${datedImages.length === 1 ? "" : "s"} positioned on the scaled timeline`;
    meta.textContent += getTimelineMode() === "selected"
        ? " • showing the selected subset"
        : " • showing the full archive";
    if (datedImages.length === 10) {
        meta.textContent += " • capped at 10 cards";
    }

    if (datedImages.length === 0) {
        shell.appendChild(createEmptyState("No dated images are available for the current timeline view."));
        return;
    }

    const minDate = Date.parse(datedImages[0].dateTaken);
    const maxDate = Date.parse(datedImages[datedImages.length - 1].dateTaken);
    const totalSpan = Math.max(maxDate - minDate, 1);
    const width = Math.max(window.innerWidth - 80, 900);

    const track = document.createElement("div");
    track.className = "timeline-track";
    track.style.width = `${width}px`;
    track.innerHTML = `<div class="timeline-axis"></div>`;

    const yearLabels = new Map();
    for (const image of datedImages) {
        const year = image.dateTaken.slice(0, 4);
        if (!yearLabels.has(year)) {
            yearLabels.set(year, image);
        }
    }

    for (const [year, image] of yearLabels) {
        const marker = document.createElement("div");
        marker.className = "timeline-year-marker";
        marker.style.left = `${((Date.parse(image.dateTaken) - minDate) / totalSpan) * (width - 120) + 60}px`;
        marker.innerHTML = `<span>${year}</span>`;
        track.appendChild(marker);
    }

    datedImages.forEach((image, index) => {
        const left = ((Date.parse(image.dateTaken) - minDate) / totalSpan) * (width - 180) + 10;
        const item = document.createElement("article");
        item.className = `timeline-item ${index % 2 === 0 ? "is-top" : "is-bottom"}`;
        item.style.left = `${left}px`;
        item.innerHTML = `
            <a class="timeline-card" href="${imageDetailUrl(image.id)}">
                <img src="${image.imageUrl}" alt="${escapeHtml(image.title)}">
                <div class="timeline-card-copy">
                    <strong>${escapeHtml(image.title)}</strong>
                    <span>${escapeHtml(image.dateTaken)}</span>
                    <span>${escapeHtml(image.paper || "Unknown paper")}</span>
                </div>
            </a>
        `;
        track.appendChild(item);
    });

    shell.appendChild(track);
}

async function loadDashboardPage() {
    const payload = await fetchImageCollection();
    state.imageCache = payload.images;
    ensureBoardStore();
    if (state.currentUser) {
        await loadFavoritesData();
    }
    renderDashboardPage();
}

function renderDashboardPage() {
    renderBoardList();
    renderDashboardSelectionList();
    renderDashboardFavoritesList();
    renderDashboardBoard();
}

function renderBoardList() {
    const list = document.getElementById("board-list");
    const meta = document.getElementById("dashboard-sidebar-meta");
    if (!list || !meta) {
        return;
    }

    const store = getBoardStore();
    list.innerHTML = "";
    meta.textContent = `${store.boards.length} of 10 boards saved`;

    for (const board of store.boards) {
        const item = document.createElement("div");
        item.className = `dashboard-sidebar-item board-list-item${store.activeBoardId === board.id ? " is-active" : ""}`;
        item.innerHTML = `
            <div>
                <strong>${escapeHtml(board.name)}</strong>
                <p>${board.items.length} card${board.items.length === 1 ? "" : "s"} • ${board.links.length} link${board.links.length === 1 ? "" : "s"}</p>
            </div>
        `;

        const openButton = document.createElement("button");
        openButton.className = "secondary-button compact";
        openButton.type = "button";
        openButton.textContent = "Open";
        openButton.addEventListener("click", () => {
            setActiveBoard(board.id);
            renderDashboardPage();
        });
        item.appendChild(openButton);

        const deleteButton = document.createElement("button");
        deleteButton.className = "secondary-button compact";
        deleteButton.type = "button";
        deleteButton.textContent = "Delete";
        deleteButton.addEventListener("click", () => {
            deleteBoard(board.id);
            renderDashboardPage();
        });
        item.appendChild(deleteButton);

        list.appendChild(item);
    }
}

function renderDashboardSelectionList() {
    const list = document.getElementById("dashboard-selection-list");
    const meta = document.getElementById("dashboard-selection-meta");
    if (!list || !meta) {
        return;
    }

    const selectedImages = state.selectedImageIds
        .map((id) => state.imageCache.find((image) => image.id === id))
        .filter(Boolean)
        .slice(0, 10);

    list.innerHTML = "";
    meta.textContent = `${selectedImages.length} selected image${selectedImages.length === 1 ? "" : "s"} available for import`;

    if (selectedImages.length === 0) {
        list.appendChild(createEmptyState("Select images in the library first, then come back here to compare them."));
        return;
    }

    for (const image of selectedImages) {
        list.appendChild(buildDashboardSourceItem(image, "Selected"));
    }
}

function renderDashboardFavoritesList() {
    const list = document.getElementById("dashboard-favorites-list");
    const meta = document.getElementById("dashboard-favorites-meta");
    if (!list || !meta) {
        return;
    }

    list.innerHTML = "";
    if (!state.currentUser) {
        meta.textContent = "Log in to show your saved favorites here";
        list.appendChild(createEmptyState("Your favorite images will appear here as draggable miniatures once you log in."));
        return;
    }

    const favoriteImages = (state.favoritesData.favorites || [])
        .map((favorite) => favorite.image)
        .filter(Boolean)
        .slice(0, 10);

    meta.textContent = `${favoriteImages.length} favorite image${favoriteImages.length === 1 ? "" : "s"} ready to drag`;
    if (favoriteImages.length === 0) {
        list.appendChild(createEmptyState("Save images as favorites to stage them here."));
        return;
    }

    for (const image of favoriteImages) {
        list.appendChild(buildDashboardSourceItem(image, "Favorite"));
    }
}

function buildDashboardSourceItem(image, label) {
    const item = document.createElement("div");
    item.className = "dashboard-source-item";
    item.draggable = true;
    item.innerHTML = `
        <img src="${image.imageUrl}" alt="${escapeHtml(image.title)}">
        <div class="dashboard-source-copy">
            <span class="dashboard-source-label">${escapeHtml(label)}</span>
            <strong>${escapeHtml(image.title)}</strong>
            <p>${escapeHtml(image.dateTaken || "Unknown date")} • ${escapeHtml(image.paper || "Unknown paper")}</p>
        </div>
    `;
    item.addEventListener("dragstart", (event) => {
        event.dataTransfer?.setData("text/image-id", image.id);
        event.dataTransfer.effectAllowed = "copy";
    });
    item.addEventListener("dblclick", () => {
        addImagesToActiveBoard([image.id]);
        renderDashboardPage();
    });
    return item;
}

function renderDashboardBoard() {
    const board = document.getElementById("dashboard-board");
    const meta = document.getElementById("dashboard-meta");
    const svg = document.getElementById("dashboard-connections");
    const title = document.getElementById("dashboard-board-title");
    if (!board || !meta || !svg || !title) {
        return;
    }

    const activeBoard = getActiveBoard();
    title.textContent = activeBoard.name;

    const itemModels = activeBoard.items
        .map((item) => ({ ...item, image: state.imageCache.find((image) => image.id === item.imageId) }))
        .filter((item) => item.image);

    meta.textContent = `${itemModels.length} card${itemModels.length === 1 ? "" : "s"} on this board`;
    board.querySelectorAll(".board-card").forEach((card) => card.remove());
    svg.innerHTML = `<defs><marker id="dashboard-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#8a3c1d"></path></marker></defs>`;

    board.querySelector(".dashboard-empty")?.remove();
    if (itemModels.length === 0) {
        const empty = createEmptyState("Select images in the library and import them here to start comparing.");
        empty.classList.add("dashboard-empty");
        board.appendChild(empty);
        return;
    }

    for (const item of itemModels) {
        const card = document.createElement("article");
        card.className = "board-card";
        card.dataset.instanceId = item.instanceId;
        card.style.left = `${item.x}px`;
        card.style.top = `${item.y}px`;
        card.innerHTML = `
            <div class="board-card-handle">
                <strong>${escapeHtml(item.image.title)}</strong>
                <span>${escapeHtml(item.image.dateTaken || "Unknown date")}</span>
            </div>
            <img src="${item.image.imageUrl}" alt="${escapeHtml(item.image.title)}">
            <p>${escapeHtml(item.image.imageCaption || "No caption added.")}</p>
            <textarea rows="3" placeholder="Comparison note..."></textarea>
            <div class="board-card-actions">
                <button class="secondary-button compact board-start-link" type="button">${state.dashboardLinkStart === item.instanceId ? "Linking..." : "Start link"}</button>
                <button class="secondary-button compact board-complete-link" type="button">Link here</button>
                <button class="secondary-button compact board-remove" type="button">Remove</button>
            </div>
        `;
        const noteField = card.querySelector("textarea");
        noteField.value = item.note || "";
        noteField.addEventListener("change", () => updateDashboardItem(item.instanceId, { note: noteField.value }));
        card.querySelector(".board-start-link").addEventListener("click", () => {
            state.dashboardLinkStart = item.instanceId;
            renderDashboardBoard();
        });
        card.querySelector(".board-complete-link").addEventListener("click", () => completeDashboardLink(item.instanceId));
        card.querySelector(".board-remove").addEventListener("click", () => removeDashboardItem(item.instanceId));
        enableDashboardDrag(card, item.instanceId);
        board.appendChild(card);
    }

    requestAnimationFrame(renderDashboardConnections);
}

function renderDashboardConnections() {
    const board = document.getElementById("dashboard-board");
    const svg = document.getElementById("dashboard-connections");
    const activeBoard = getActiveBoard();
    if (!board || !svg) {
        return;
    }
    const boardRect = board.getBoundingClientRect();
    svg.innerHTML = `<defs><marker id="dashboard-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L9,3 z" fill="#8a3c1d"></path></marker></defs>`;

    for (const link of activeBoard.links) {
        const fromCard = board.querySelector(`[data-instance-id="${link.from}"]`);
        const toCard = board.querySelector(`[data-instance-id="${link.to}"]`);
        if (!fromCard || !toCard) {
            continue;
        }
        const fromRect = fromCard.getBoundingClientRect();
        const toRect = toCard.getBoundingClientRect();
        const x1 = fromRect.left - boardRect.left + fromRect.width / 2;
        const y1 = fromRect.top - boardRect.top + fromRect.height / 2;
        const x2 = toRect.left - boardRect.left + toRect.width / 2;
        const y2 = toRect.top - boardRect.top + toRect.height / 2;

        const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
        line.setAttribute("x1", x1);
        line.setAttribute("y1", y1);
        line.setAttribute("x2", x2);
        line.setAttribute("y2", y2);
        line.setAttribute("stroke", "#8a3c1d");
        line.setAttribute("stroke-width", "2.5");
        line.setAttribute("marker-end", "url(#dashboard-arrow)");
        line.classList.add("dashboard-link-line");
        line.style.pointerEvents = "stroke";
        line.addEventListener("click", () => {
            removeDashboardLink(link.from, link.to);
        });
        svg.appendChild(line);
    }
}

function addImagesToActiveBoard(imageIds) {
    ensureBoardStore();
    const activeBoard = getActiveBoard();
    const existing = new Set(activeBoard.items.map((item) => item.imageId));
    const nextItems = [...activeBoard.items];
    for (const imageId of imageIds) {
        if (nextItems.length >= 10) {
            break;
        }
        if (existing.has(imageId)) {
            continue;
        }
        nextItems.push({
            instanceId: cryptoRandomId(),
            imageId,
            x: 40 + nextItems.length * 24,
            y: 40 + nextItems.length * 24,
            note: "",
        });
    }
    updateBoard(activeBoard.id, { items: nextItems });
}

function removeDashboardItem(instanceId) {
    const board = getActiveBoard();
    updateBoard(board.id, {
        items: board.items.filter((item) => item.instanceId !== instanceId),
        links: board.links.filter((link) => link.from !== instanceId && link.to !== instanceId),
    });
    if (state.dashboardLinkStart === instanceId) {
        state.dashboardLinkStart = null;
    }
    renderDashboardPage();
}

function updateDashboardItem(instanceId, updates) {
    const board = getActiveBoard();
    updateBoard(board.id, {
        items: board.items.map((item) => item.instanceId === instanceId ? { ...item, ...updates } : item),
    });
}

function completeDashboardLink(targetInstanceId) {
    if (!state.dashboardLinkStart || state.dashboardLinkStart === targetInstanceId) {
        return;
    }
    const board = getActiveBoard();
    const exists = board.links.some((link) => link.from === state.dashboardLinkStart && link.to === targetInstanceId);
    if (!exists) {
        updateBoard(board.id, {
            links: [...board.links, { from: state.dashboardLinkStart, to: targetInstanceId }],
        });
    }
    state.dashboardLinkStart = null;
    renderDashboardBoard();
}

function removeDashboardLink(fromInstanceId, toInstanceId) {
    const board = getActiveBoard();
    updateBoard(board.id, {
        links: board.links.filter((link) => !(link.from === fromInstanceId && link.to === toInstanceId)),
    });
    renderDashboardBoard();
}

function enableDashboardDrag(card, instanceId) {
    const handle = card.querySelector(".board-card-handle");
    handle.addEventListener("mousedown", (event) => {
        event.preventDefault();
        const startX = event.clientX;
        const startY = event.clientY;
        const initialLeft = parseFloat(card.style.left) || 0;
        const initialTop = parseFloat(card.style.top) || 0;

        const onMove = (moveEvent) => {
            const nextLeft = Math.max(0, initialLeft + moveEvent.clientX - startX);
            const nextTop = Math.max(0, initialTop + moveEvent.clientY - startY);
            card.style.left = `${nextLeft}px`;
            card.style.top = `${nextTop}px`;
            renderDashboardConnections();
        };

        const onUp = () => {
            document.removeEventListener("mousemove", onMove);
            document.removeEventListener("mouseup", onUp);
            updateDashboardItem(instanceId, {
                x: parseFloat(card.style.left) || 0,
                y: parseFloat(card.style.top) || 0,
            });
            renderDashboardBoard();
        };

        document.addEventListener("mousemove", onMove);
        document.addEventListener("mouseup", onUp);
    });
}

function debounce(fn, delay) {
    let timeoutId;
    return (...args) => {
        clearTimeout(timeoutId);
        timeoutId = setTimeout(() => fn(...args), delay);
    };
}

async function apiRequest(url, options = {}) {
    const response = await fetch(url, {
        headers: {
            "Content-Type": "application/json",
            ...(options.headers || {}),
        },
        credentials: "same-origin",
        ...options,
    });
    const text = await response.text();
    const payload = text ? JSON.parse(text) : {};
    if (!response.ok) {
        throw new Error(payload.error || "Request failed.");
    }
    return payload;
}

function imageDetailUrl(id) {
    return `/image.html?id=${encodeURIComponent(id)}`;
}

function currentImageId() {
    return new URLSearchParams(window.location.search).get("id");
}

function formatMetaLine(image) {
    const parts = [];
    if (image.dateTaken) {
        parts.push(`Taken: ${image.dateTaken}`);
    }
    if (image.paper) {
        parts.push(`Paper: ${image.paper}`);
    }
    if (image.creator) {
        parts.push(`Creator: ${image.creator}`);
    }
    return parts.length > 0 ? parts.join(" • ") : "No paper or creator metadata added yet.";
}

function formatFileSize(bytes) {
    const value = Number(bytes);
    if (!Number.isFinite(value) || value <= 0) {
        return "";
    }
    if (value < 1024) {
        return `${value} bytes`;
    }
    if (value < 1024 * 1024) {
        return `${(value / 1024).toFixed(1)} KB`;
    }
    return `${(value / (1024 * 1024)).toFixed(2)} MB`;
}

function formatCameraMetadata(metadata) {
    if (!metadata || Object.keys(metadata).length === 0) {
        return "";
    }
    return Object.entries(metadata).map(([key, value]) => `${key}: ${value}`).join(" | ");
}

function compareDates(left, right) {
    const leftValue = Date.parse(left || "");
    const rightValue = Date.parse(right || "");
    return (Number.isFinite(leftValue) ? leftValue : -Infinity) - (Number.isFinite(rightValue) ? rightValue : -Infinity);
}

function compareText(left, right) {
    return String(left || "").localeCompare(String(right || ""), undefined, { sensitivity: "base" });
}

function createEmptyState(text) {
    const node = document.createElement("div");
    node.className = "empty-state";
    node.textContent = text;
    return node;
}

function selectedImageStorageKey() {
    return `comparison_selected_images_${state.currentUser?.username || "guest"}`;
}

function getSelectedImageIds() {
    try {
        const raw = localStorage.getItem(selectedImageStorageKey());
        if (!raw) {
            return [];
        }
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
    } catch (error) {
        return [];
    }
}

function persistSelectedImageIds() {
    localStorage.setItem(selectedImageStorageKey(), JSON.stringify(state.selectedImageIds));
}

function boardStorageKey() {
    return `comparison_boards_${state.currentUser?.username || "guest"}`;
}

function ensureBoardStore() {
    const raw = localStorage.getItem(boardStorageKey());
    if (raw) {
        try {
            const parsed = JSON.parse(raw);
            if (Array.isArray(parsed.boards) && parsed.boards.length > 0) {
                return parsed;
            }
        } catch (error) {
        }
    }
    const store = {
        activeBoardId: "board-1",
        boards: [
            { id: "board-1", name: "Board 1", items: [], links: [] },
        ],
    };
    saveBoardStore(store);
    return store;
}

function getBoardStore() {
    return ensureBoardStore();
}

function saveBoardStore(store) {
    localStorage.setItem(boardStorageKey(), JSON.stringify(store));
}

function getActiveBoard() {
    const store = getBoardStore();
    return store.boards.find((board) => board.id === store.activeBoardId) || store.boards[0];
}

function setActiveBoard(boardId) {
    const store = getBoardStore();
    store.activeBoardId = boardId;
    saveBoardStore(store);
}

function createBoard(name) {
    const store = getBoardStore();
    const board = { id: cryptoRandomId(), name, items: [], links: [] };
    store.boards.push(board);
    store.activeBoardId = board.id;
    saveBoardStore(store);
}

function deleteBoard(boardId) {
    const store = getBoardStore();
    if (store.boards.length === 1) {
        store.boards[0] = { ...store.boards[0], items: [], links: [] };
    } else {
        store.boards = store.boards.filter((board) => board.id !== boardId);
        if (store.activeBoardId === boardId) {
            store.activeBoardId = store.boards[0].id;
        }
    }
    saveBoardStore(store);
}

function updateBoard(boardId, updates) {
    const store = getBoardStore();
    store.boards = store.boards.map((board) => board.id === boardId ? { ...board, ...updates } : board);
    saveBoardStore(store);
}

function clearActiveBoard() {
    const board = getActiveBoard();
    updateBoard(board.id, { items: [], links: [] });
}

function timelineModeStorageKey() {
    return `timeline_mode_${state.currentUser?.username || "guest"}`;
}

function getTimelineMode() {
    return localStorage.getItem(timelineModeStorageKey()) || "all";
}

function persistTimelineMode(mode) {
    localStorage.setItem(timelineModeStorageKey(), mode);
}

function cryptoRandomId() {
    if (window.crypto?.randomUUID) {
        return window.crypto.randomUUID();
    }
    return `dash-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}
