package ibm.com.visual_recognition;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.flexbox.FlexboxLayout;
import com.ibm.watson.developer_cloud.service.exception.ForbiddenException;
import com.ibm.watson.developer_cloud.visual_recognition.v3.VisualRecognition;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.ClassifyImagesOptions;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.DetectedFaces;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.Face;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.ImageClassification;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.ImageFace;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.VisualClassification;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.VisualClassifier;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.VisualRecognitionOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.ibm.mobilefirstplatform.clientsdk.android.core.api.BMSClient;




public class MainActivity extends AppCompatActivity {

    

    private static final String STATE_IMAGE = "image";
    private static final String STATE_TAG_NAME = "tagNames";
    private static final String STATE_TAG_SCORES = "tagScores";

    private static final String EXCEPTION_NO_IMAGE_RECOGNITION = "noImageRecognition";
    private static final String EXCEPTION_NO_INTERNET = "noInternet";
    private static final String EXCEPTION_UNKNOWN = "unknown";
    private static final String VALID_CREDENTIALS = "valid";

    private static final int REQUEST_CAMERA = 1;
    private static final int REQUEST_GALLERY = 2;
    private static final float MAX_IMAGE_DIMENSION = 1200;

    private VisualRecognition visualService;

    private ArrayList<ImageTag> allTags;
    private String mSelectedImageUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        allTags = new ArrayList<ImageTag>();

        if (savedInstanceState != null) {
            ArrayList<String> tagNames = savedInstanceState.getStringArrayList(STATE_TAG_NAME);
            ArrayList<String> tagScores = savedInstanceState.getStringArrayList(STATE_TAG_SCORES);
            mSelectedImageUri = savedInstanceState.getString(STATE_IMAGE);

            // Re-fetch the selected Bitmap from its Uri, or if null, restore the default image.
            if (mSelectedImageUri != null) {
                Uri imageUri = Uri.parse(mSelectedImageUri);
                Bitmap selectedImage = fetchBitmapFromUri(imageUri);

                ImageView selectedImageView = (ImageView) findViewById(R.id.selectedImageView);
                selectedImageView.setImageBitmap(selectedImage);

            } else {
                ImageView selectedImageView = (ImageView) findViewById(R.id.selectedImageView);
                selectedImageView.setImageDrawable(getResources().getDrawable(R.mipmap.bend));
            }

            // Restore all tags assigned to the previous instance
            for (int i = 0; i < tagNames.size(); i++) {
                allTags.add(new ImageTag(tagNames.get(i), tagScores.get(i)));
            }

            createUITags(allTags);
        } else {
            // On first run plant "fake" tags (obtained from Watson previously) for display purposes
            allTags.add(new ImageTag("Blue Sky", "85%"));
            allTags.add(new ImageTag("Landscape", "60%"));

            createUITags(allTags);
        }

        ImageButton cameraButton = (ImageButton) findViewById(R.id.cameraButton);
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                startActivityForResult(cameraIntent, REQUEST_CAMERA);
            }
        });

        ImageButton galleryButton = (ImageButton) findViewById(R.id.galleryButton);
        galleryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent galleryIntent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                startActivityForResult(galleryIntent, REQUEST_GALLERY);
            }
        });

        // Core SDK must be initialized to interact with Bluemix mobile services
        BMSClient.getInstance().initialize(getApplicationContext(), BMSClient.REGION_US_SOUTH);

        

        visualService = new VisualRecognition(VisualRecognition.VERSION_DATE_2016_05_20,getString(R.string.watson_visual_recognition_api_key));

        ValidateCredentialsTask vct = new ValidateCredentialsTask();
        vct.execute();
    }

    @Override
    public void onResume() {
        super.onResume();
        
        
        
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    protected void onSaveInstanceState(Bundle savedInstanceState) {

        // Want to save our Selected Image's URI and all Tags
        ArrayList<String> tagNames = new ArrayList<String>();
        ArrayList<String> tagScores = new ArrayList<String>();

        for (int i = 0; i < allTags.size(); i++) {
            tagNames.add(allTags.get(i).getTagName());
            tagScores.add(allTags.get(i).getTagScore());
        }

        savedInstanceState.putString(STATE_IMAGE, mSelectedImageUri);
        savedInstanceState.putStringArrayList(STATE_TAG_NAME, tagNames);
        savedInstanceState.putStringArrayList(STATE_TAG_SCORES, tagScores);

        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_GALLERY || requestCode == REQUEST_CAMERA) {
                Uri uri = data.getData();

                mSelectedImageUri = uri.toString();

                // Fetch the Bitmap from the Uri
                Bitmap selectedImage = fetchBitmapFromUri(uri);

                // Set the UI Bitmap with the full-sized, rotated Bitmap
                ImageView resultImage = (ImageView) findViewById(R.id.selectedImageView);
                resultImage.setImageBitmap(selectedImage);

                // Resize the Bitmap to constrain within Watson Image Recognition's Size Limit
                selectedImage = resizeBitmapForWatson(selectedImage);

                // Send the resized, rotated, bitmap to the Classify Task for Asynch Classification
                ClassifyTask ct = new ClassifyTask();
                ct.execute(selectedImage);
            }
        }
    }

    private class ValidateCredentialsTask extends AsyncTask<Void, Void, String> {

        @Override
        protected String doInBackground(Void... params) {

            // To test validity we do a quick network request to the service and switch on result
            try {
                visualService.getClassifiers().execute();
            } catch (Exception e) {
                if (e.getClass().equals(ForbiddenException.class) ||
                        e.getClass().equals(IllegalArgumentException.class)) {
                    return EXCEPTION_NO_IMAGE_RECOGNITION;
                }
                else if (e.getCause().getClass().equals(UnknownHostException.class)) {
                    return EXCEPTION_NO_INTERNET;
                }
                else {
                    e.printStackTrace();
                    return EXCEPTION_UNKNOWN;
                }
            }

            return VALID_CREDENTIALS;
        }

        @Override
        protected void onPostExecute(String result) {

            if (result.equals(VALID_CREDENTIALS))
                return;

            AlertDialog alertDialog = new AlertDialog.Builder(MainActivity.this).create();
            switch (result) {
                case EXCEPTION_NO_IMAGE_RECOGNITION:
                    alertDialog.setTitle("Invalid ImageRecognition Credentials");
                    alertDialog.setMessage("Failed to connect to the ImageRecognition service due to invalid credentials.\n" +
                            "Please verify your credentials in \"values/watson_credentials\" and rebuild the application. \n" +
                            "See the README for further assistance.");

                    alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Close Application",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int pos) {
                                    MainActivity.this.finish();
                                }
                            });
                    break;

                case EXCEPTION_NO_INTERNET:
                    alertDialog.setTitle("Cannot Connect to Bluemix");
                    alertDialog.setMessage("Failed to connect to Bluemix.\n" +
                            "Please verify your connection to the internet. \n" +
                            "See the README for further assistance.");

                    alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Close Application",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int pos) {
                                    MainActivity.this.finish();
                                }
                            });
                    break;

                default:
                    alertDialog.setTitle("Unknown Error");
                    alertDialog.setMessage("Failed to Verify Credentials.\n" +
                            "Please see console output. \n" +
                            "See the README for further assistance.");
                    alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Close Application",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int pos) {
                                    MainActivity.this.finish();
                                }
                            });
                    break;
            }
            alertDialog.show();
        }
    }

    private class ClassifyTask extends AsyncTask<Bitmap, Void, ClassifyTaskResult> {

        @Override
        protected void onPreExecute() {

            // Clear the current Tag UI
            FlexboxLayout tagContainerLayout = (FlexboxLayout)findViewById(R.id.tagContainerView);
            tagContainerLayout.removeAllViewsInLayout();
            allTags.clear();

            // Show the spinner
            ProgressBar progressSpinner = (ProgressBar)findViewById(R.id.loadingSpinner);
            progressSpinner.setVisibility(View.VISIBLE);
        }

        @Override
        protected ClassifyTaskResult doInBackground(Bitmap... params) {
            Bitmap createdPhoto = params[0];

            // Reformat Bitmap into a .jpg and save as file to input to Watson
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            createdPhoto.compress(Bitmap.CompressFormat.JPEG, 100, bytes);

            try {
                File tempPhoto = File.createTempFile("photo", ".jpg", getCacheDir());
                FileOutputStream out = new FileOutputStream(tempPhoto);
                out.write(bytes.toByteArray());
                out.close();

                // Two different calls for objects and for faces
                ClassifyImagesOptions classifyImagesOptions = new ClassifyImagesOptions.Builder().images(tempPhoto).build();
                VisualRecognitionOptions recognitionOptions = new VisualRecognitionOptions.Builder().images(tempPhoto).build();

                VisualClassification classification = visualService.classify(classifyImagesOptions).execute();
                DetectedFaces faces = visualService.detectFaces(recognitionOptions).execute();

                ClassifyTaskResult result = new ClassifyTaskResult(classification, faces);

                tempPhoto.delete();

                return result;

            } catch (Exception e) {
                e.printStackTrace();
            }

            return null;
        }
        @Override
        protected void onPostExecute(ClassifyTaskResult result) {

            ProgressBar progressSpinner = (ProgressBar)findViewById(R.id.loadingSpinner);
            progressSpinner.setVisibility(View.GONE);

            processClassificationData(result);
        }
    }

    private class ClassifyTaskResult {
        private final VisualClassification visualClassification;
        private final DetectedFaces detectedFaces;

        public ClassifyTaskResult (VisualClassification vcIn, DetectedFaces dfIn) {
            visualClassification = vcIn;
            detectedFaces = dfIn;
        }

        public VisualClassification getVisualClassification() { return visualClassification;}
        public DetectedFaces getDetectedFaces() {return detectedFaces;}
    }

    private class ImageTag {
        private final String tagName;
        private final String tagScore;

        public ImageTag (String tagNameIn, String tagScoreIn) {
            tagName = tagNameIn;
            tagScore = tagScoreIn;
        }

        public ImageTag (String tagNameIn, double rawScoreIn) {
            tagName = tagNameIn;
            tagScore = String.format(Locale.US, "%.0f", rawScoreIn * 100) + "%";
        }

        public String getTagName() {return tagName;}
        public String getTagScore() {return tagScore;}
    }

    private void processClassificationData(ClassifyTaskResult result) {

        VisualClassification visualClassification = result.getVisualClassification();
        DetectedFaces detectedFaces = result.getDetectedFaces();

        // Extract all Face data from Watson and turn it into Tags
        List<ImageFace> potentialFaces = detectedFaces.getImages();
        for (int i = 0; i < potentialFaces.size(); i++)
        {
            List<Face> allFaces = potentialFaces.get(i).getFaces();
            if (allFaces == null) {break;}
            for (int j = 0; j < allFaces.size(); j++) {
                Face identifiedFace = allFaces.get(j);

                if (identifiedFace.getIdentity() != null) {
                    allTags.add(new ImageTag(identifiedFace.getIdentity().getName(),
                                             identifiedFace.getIdentity().getScore()));
                }

                // Grab gender and Age
                Face.Gender gender = identifiedFace.getGender();
                Face.Age age = identifiedFace.getAge();

                String resultString = "";

                // Unique situation for Gender + Age as it has 2 Scores
                String resultScore = "";

                if (gender.getGender() != null) {
                    resultString += gender.getGender();
                    resultScore += String.format(Locale.US, "%.0f", gender.getScore() * 100) + "%";
                } else {
                    resultString += "Unknown Gender";
                    resultScore += "N/A";
                }

                if (age != null) {
                    if (age.getMin() == null) {age.setMin(0);}
                    if (age.getMax() == null) {age.setMax(age.getMin()+15);}
                    resultString += " (" + age.getMin() + " - " + age.getMax() + ")";
                    resultScore += " (" + String.format(Locale.US, "%.0f", age.getScore() * 100) + "%)";
                }

                allTags.add(new ImageTag(resultString, resultScore));
            }
        }

        // Extracting all the class names and scores from the classifications returned from Watson
        List<ImageClassification> classifications = visualClassification.getImages();
        for (int i = 0; i < classifications.size(); i++) {
            List<VisualClassifier> classifiers = classifications.get(i).getClassifiers();
            if (classifiers == null) break;
            for (int j = 0; j < classifiers.size(); j++) {
                List<VisualClassifier.VisualClass> visualClasses = classifiers.get(j).getClasses();
                if (visualClasses == null) break;
                for (int k = 0; k < visualClasses.size(); k++) {
                    String className = visualClasses.get(k).getName();
                    double score = visualClasses.get(k).getScore();

                    allTags.add(new ImageTag(className, score));
                }
            }
        }

        // If Watson identified anything turn the data into UI Tags, otherwise Toast an update to the user
        if (allTags.size() > 0) {
            createUITags(allTags);
        } else {
            Toast.makeText(getApplicationContext(),
                    "imagerecog is unable to classify anything in the photo!",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void createUITags(final ArrayList<ImageTag> allTags) {

        FlexboxLayout tagContainerLayout = (FlexboxLayout)findViewById(R.id.tagContainerView);
        tagContainerLayout.removeAllViewsInLayout();

        for (int i = 0; i < allTags.size(); i++) {
            TextView imageTagView = (TextView)getLayoutInflater().inflate(R.layout.image_tag, null);
            imageTagView.setText(allTags.get(i).getTagName());

            // When a tag is clicked I want it to toggle between showing Score and showing Name
            imageTagView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FlexboxLayout tagContainerLayout = (FlexboxLayout)findViewById(R.id.tagContainerView);
                    TextView tagView = (TextView)v;

                    int viewLocation;
                    for (viewLocation = 0; viewLocation < tagContainerLayout.getChildCount(); viewLocation++){
                        if (tagView.equals(tagContainerLayout.getChildAt(viewLocation)))
                            break;
                    }

                    ImageTag selectedTag = allTags.get(viewLocation);
                    String currentText = tagView.getText().toString();

                    if (currentText.equals(selectedTag.getTagName())) {
                        //Locking the size of the textview so that it doesn't change the UI on text change
                        tagView.setMinWidth(tagView.getWidth());
                        tagView.setText(selectedTag.getTagScore());
                    } else {
                        tagView.setText(selectedTag.getTagName());
                    }
                }
            });
            tagContainerLayout.addView(imageTagView);
        }
    }

    private Bitmap fetchBitmapFromUri(Uri imageUri) {
        try {
            // Fetch the Bitmap from the Uri
            Bitmap selectedImage = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);

            // Fetch the orientation of the Bitmap in storage to rotate correctly
            String[] orientationColumn = {MediaStore.Images.Media.ORIENTATION};
            Cursor cursor = getContentResolver().query(imageUri, orientationColumn, null, null, null);
            int orientation = 0;
            if (cursor != null && cursor.moveToFirst()) {
                orientation = cursor.getInt(cursor.getColumnIndex(orientationColumn[0]));
            }
            cursor.close();

            // Rotate the bitmap with the found orientation
            Matrix matrix = new Matrix();
            matrix.setRotate(orientation);
            selectedImage = Bitmap.createBitmap(selectedImage, 0, 0, selectedImage.getWidth(), selectedImage.getHeight(), matrix, true);

            return selectedImage;

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Bitmap resizeBitmapForWatson(Bitmap originalImage) {

        int originalHeight = originalImage.getHeight();
        int originalWidth = originalImage.getWidth();

        int boundingDimension = (originalHeight > originalWidth) ? originalHeight : originalWidth;

        float scale = MAX_IMAGE_DIMENSION / boundingDimension;

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);

        originalImage = Bitmap.createBitmap(originalImage, 0, 0, originalWidth, originalHeight, matrix, true);

        return originalImage;
    }
}
