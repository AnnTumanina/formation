package ru.compot.corrector.core;


import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.text.Font;
import org.languagetool.JLanguageTool;
import org.languagetool.language.BritishEnglish;
import org.languagetool.language.French;
import org.languagetool.language.GermanyGerman;
import org.languagetool.language.Russian;
import org.languagetool.rules.RuleMatch;
import ru.compot.corrector.utils.AreaUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

public class AnalyzerCore {

    /**
     * Путь до файла конфигурации приложения
     */
    private static final String PREFERENCES_PATH = System.getProperty("user.home") + File.separator + "TextCorrector";

    /**
     * Анализатор русского языка
     */
    private final JLanguageTool russianAnalyzer;
    /**
     * Анализатор английского языка
     */
    private final JLanguageTool englishAnalyzer;
    /**
     * Настройки приложения (массив пар строка-строка)
     */
    private final Properties properties = new Properties();

    /**
     * Последние проанализируемые регионы слов
     */
    private final List<AnalyzedRegion> analyzedRegions = new CopyOnWriteArrayList<>();

    public AnalyzerCore() {
        try (FileInputStream fis = new FileInputStream(PREFERENCES_PATH + File.separator + "preferences.properties")) {
            properties.load(fis);
        } catch (IOException ignored) {
        }
        // ---- создаем анализаторы ----
        russianAnalyzer = new JLanguageTool(new Russian());
        englishAnalyzer = new JLanguageTool(new BritishEnglish());
    }

    /**
     * Получение смещения символов по индексу
     * Смещение происходит при замене слов с разным количеством символов
     * В проанализируемом тексте все последующие символы после символа "д" сместятся на 1 символ назад (смещение = -1).
     * @param offsets карта всех смещений, получаемых по позиции символа в тексте
     * @param fromPosition позиция символа в тексте
     * @return смещение символов
     */
    public static int getOffset(Map<Integer, Integer> offsets, int fromPosition) {
        return offsets.keySet() 
                .stream() 
                .filter(i -> fromPosition >= i) 
                .mapToInt(offsets::get) 
                .sum();
    }

    /**
     * Получение позиции проанализированного региона на экране относительно поля для вывода текста
     * @param outputAreaBounds границы текстового поля
     * @param outputAreaFont шрифт текстового поля
     * @param beforeText весь текст, находящийся перед регионом
     * @param textBounds границы региона
     * @return точку верхнего левого угла проанализированного региона
     */
    public static Point2D getMatchPosition(Bounds outputAreaBounds, Font outputAreaFont, String beforeText, Bounds textBounds) {
        double regionX = outputAreaBounds.getMinX()
                + AreaUtils.getLastLineWidth(outputAreaBounds, outputAreaFont, beforeText); // вычисляем x
        double regionY = outputAreaBounds.getMinY()
                + (AreaUtils.getLinesInArea(outputAreaBounds, outputAreaFont, beforeText) - 1)
                * (textBounds.getHeight() + 1); // вычисляем y
        if (regionX + textBounds.getWidth() > outputAreaBounds.getWidth()) { // если (x + размер текста) больше чем длина поля для вывода
            regionX = outputAreaBounds.getMinX(); // x обнуляем
            regionY += textBounds.getHeight() + 1; // y переносим на новую строку
        }
        return new Point2D(regionX, regionY);
    }

    /**
     * Включено ли разделение текста на абзаце
     */
    public boolean isParagraphsEnabled() {
        return Boolean.parseBoolean((String) properties.getOrDefault("paragraphs.enabled", "true"));
    }

    /**
     * Устанавливает настройку разделения текста на абзацы
     * @param enabled значение
     */
    public void setParagraphsEnabled(boolean enabled) {
        properties.put("paragraphs.enabled", enabled);
    }

    /**
     * Возвращает количество предложений в абзаце. Используется если "paragraphs.enabled" = true
     * @return число предложений
     */
    public int getSentencesInParagraph() {
        int result = 3;
        try {
            result = Integer.parseInt((String) properties.getOrDefault("paragraphs.sentences", "3"));
        } catch (NumberFormatException e) {
            properties.remove("paragraphs.sentences");
        }
        return result;
    }

    /**
     * Устанавливает настройку количества предложений в абзаце
     * @param sentences число предложений
     */
    public void setSentencesInParagraph(int sentences) {
        properties.put("paragraphs.sentences", String.valueOf(sentences));
    }

    /**
     * Включен ли анализ английского языка
     */
    public boolean isEnglishEnabled() {
        return Boolean.parseBoolean((String) properties.getOrDefault("analyzers.english", "false"));
    }

    /**
     * Устанавливает настройку анализа английского языка
     * @param enabled значение
     */
    public void setEnglishEnabled(boolean enabled) {
        properties.put("analyzers.english", String.valueOf(enabled));
    }

    /**
     * Геттер analyzedRegions
     */
    public List<AnalyzedRegion> getAnalyzedRegions() {
        return analyzedRegions;
    }

    /**
     * Запускает анализ текста
     * @param input текст
     * @return объект AnalyzerOutput с исправлениями или null, если произошла ошибка
     */
    public AnalyzerOutput analyze(String input) {
        try {
            CopyOnWriteArrayList<RuleMatch> matches = new CopyOnWriteArrayList<>(); // создаем список исправлений
            // ---- анализируем и добавляем исправления в лист ----
            russianAnalyzer.check(input, matches::add);
            if (isEnglishEnabled()) englishAnalyzer.check(input, matches::add);
            return new AnalyzerOutput(input, matches);
        } catch (Throwable e) {
            e.printStackTrace();
            return null; // при ошибке вернем пустоту
        }
    }

    /**
     * Создает список проанализируемых регионов
     * @param output данные с метода analyze()
     * @param offsets смещения символов
     * @param outputAreaBounds границы поля для вывода текста
     * @param outputAreaFont шрифт поля для вывода текста
     */
    public void applyAnalyzedRegions(AnalyzerOutput output, Map<Integer, Integer> offsets, String outputText, Bounds outputAreaBounds, Font outputAreaFont) {
        for (RuleMatch rm : output.matches()) {
            int offset = AnalyzerCore.getOffset(offsets, rm.getFromPos());
            String part1 = outputText.substring(0, rm.getFromPos() + offset);
            String source = output.inputText().substring(rm.getFromPos(), rm.getToPos()); 
            String replacement = rm.getSuggestedReplacements().size() > 0 ? rm.getSuggestedReplacements().get(0) : source; 
            AnalyzedRegion ar = new AnalyzedRegion( 
                    rm.getFromPos() + offset, 
                    source, 
                    replacement, 
                    rm.getSuggestedReplacements() 
            );
            ar.updatePosition(outputAreaBounds, outputAreaFont, part1); // обновляем позицию этого региона
            analyzedRegions.add(ar); // добавляем в список проанализированных регионов
        }
    }

    /**
     * Сохраняет текущие настройки приложения в файл
     */
    public void save() {
        try {
            File file = new File(PREFERENCES_PATH + File.separator + "preferences.properties"); // получаем файл для сохранения
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            }
            FileOutputStream fos = new FileOutputStream(file);
            properties.store(fos, null); // записываем
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
